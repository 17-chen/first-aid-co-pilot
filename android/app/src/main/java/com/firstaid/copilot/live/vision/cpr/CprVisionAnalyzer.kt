package com.firstaid.copilot.live.vision.cpr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.atomic.AtomicInteger

class CprVisionAnalyzer(
    context: Context,
    private val onMetrics: (Map<String, Any?>) -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val cameraFacing: VisionCameraFacing = VisionCameraFacing.Front,
    private val cameraMount: VisionCameraMount = VisionCameraMount.SideFixed,
    private val mirrored: Boolean = cameraFacing == VisionCameraFacing.Front,
    private val deriver: CprMetricsDeriver = CprMetricsDeriver(
        CprMetricsDeriver.Options(mirrorX = mirrored),
    ),
    /**
     * Minimum spacing between submitted frames, i.e. a ~30fps ceiling (1000/33).
     * This only caps the *upper* bound: when GPU throughput would exceed 30fps it
     * saves power/heat by skipping the surplus; when inference is slower than 33ms
     * the [maxConcurrentDetections] backpressure already gates submission, so the
     * floor is inert and the two never stack to drop below the inference-limited
     * rate.
     */
    private val minFrameIntervalMs: Long = 33L,
    /**
     * Bound how many frames MediaPipe is processing at once. Capping at 1 keeps
     * end-to-end latency at ~one inference (no queue buildup), which is the main
     * lever against the "feels laggy" complaint; [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]
     * still guarantees we always submit the freshest frame.
     */
    private val maxConcurrentDetections: Int = 1,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val appContext = context.applicationContext
    private var landmarker: PoseLandmarker? = null
    private var lastSubmittedTimestampMs: Long = Long.MIN_VALUE
    private var unavailableReported = false

    /** In-flight detections; gates submission so we never outrun MediaPipe. */
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var lastResultElapsedMs: Long = SystemClock.elapsedRealtime()

    /** Upright frame dimensions (after rotation) handed to the overlay for FILL_CENTER mapping. */
    @Volatile
    private var uprightWidth: Int = 0

    @Volatile
    private var uprightHeight: Int = 0

    private var cachedRotationDegrees: Int = Int.MIN_VALUE
    private var cachedProcessingOptions: ImageProcessingOptions? = null

    /** Honest description of the active inference backend (GPU vs CPU) for logs/metrics. */
    @Volatile
    var activeDelegate: String = "unknown"
        private set

    init {
        landmarker = createLandmarker()
    }

    override fun analyze(image: ImageProxy) {
        val poseLandmarker = landmarker
        if (poseLandmarker == null) {
            image.close()
            return
        }

        val rotationDegrees = image.imageInfo.rotationDegrees
        val rawTimestampMs = image.imageInfo.timestamp / NANOS_PER_MILLI
        val timestampMs = if (lastSubmittedTimestampMs == Long.MIN_VALUE) {
            rawTimestampMs
        } else {
            maxOf(rawTimestampMs, lastSubmittedTimestampMs + 1)
        }

        if (!shouldSubmit(timestampMs)) {
            image.close()
            return
        }

        val bitmap = runCatching { image.toBitmap() }
            .onFailure { reportUnavailable("姿态分析失败：${it.message ?: it.javaClass.simpleName}") }
            .getOrNull()
        image.close()
        if (bitmap == null) return

        runCatching {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val processingOptions = processingOptionsFor(rotationDegrees)
            updateUprightDimensions(bitmap.width, bitmap.height, rotationDegrees)
            lastSubmittedTimestampMs = timestampMs
            inFlight.incrementAndGet()
            poseLandmarker.detectAsync(mpImage, processingOptions, timestampMs)
        }.onFailure {
            inFlight.decrementAndGet()
            reportUnavailable("姿态分析失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * Backpressure + optional frame-rate floor. We skip a frame while another is
     * still being processed, but self-heal if a callback was never delivered so a
     * lost result can't permanently freeze the overlay.
     */
    private fun shouldSubmit(timestampMs: Long): Boolean {
        if (inFlight.get() >= maxConcurrentDetections) {
            val stalledMs = SystemClock.elapsedRealtime() - lastResultElapsedMs
            if (stalledMs > STALL_RESET_MS) {
                inFlight.set(0)
            } else {
                return false
            }
        }
        if (minFrameIntervalMs > 0 &&
            lastSubmittedTimestampMs != Long.MIN_VALUE &&
            timestampMs - lastSubmittedTimestampMs < minFrameIntervalMs
        ) {
            return false
        }
        return true
    }

    override fun close() {
        landmarker?.close()
        landmarker = null
    }

    private fun createLandmarker(): PoseLandmarker? {
        if (!assetExists(MODEL_ASSET_NAME)) {
            reportUnavailable("缺少姿态模型 assets/$MODEL_ASSET_NAME，仅保留相机预览，不上报实时识别。")
            return null
        }

        // GPU first for lower per-frame latency; fall back to CPU (XNNPACK) if the
        // device can't create a GPU delegate so we never crash the live session.
        val gpuLandmarker = runCatching { buildLandmarker(Delegate.GPU) }
            .onFailure { Log.w(TAG, "GPU pose delegate unavailable, falling back to CPU: ${it.message}") }
            .getOrNull()
        if (gpuLandmarker != null) {
            activeDelegate = "gpu"
            Log.i(TAG, "Pose landmarker created with GPU delegate")
            return gpuLandmarker
        }

        val cpuLandmarker = runCatching { buildLandmarker(Delegate.CPU) }
            .onFailure { reportUnavailable("姿态模型初始化失败：${it.message ?: it.javaClass.simpleName}") }
            .getOrNull()
        if (cpuLandmarker != null) {
            activeDelegate = "cpu"
            Log.i(TAG, "Pose landmarker created with CPU delegate")
        }
        return cpuLandmarker
    }

    private fun buildLandmarker(delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_NAME)
            .setDelegate(delegate)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onDetectionResult(result) }
            .setErrorListener { error ->
                inFlight.set(0)
                lastResultElapsedMs = SystemClock.elapsedRealtime()
                reportUnavailable("姿态模型运行错误：${error.message ?: error.javaClass.simpleName}")
            }
            .build()
        return PoseLandmarker.createFromOptions(appContext, options)
    }

    private fun onDetectionResult(result: PoseLandmarkerResult) {
        inFlight.decrementAndGet()
        lastResultElapsedMs = SystemClock.elapsedRealtime()
        handleResult(result)
    }

    private fun handleResult(result: PoseLandmarkerResult) {
        val pose = result.landmarks().firstOrNull()
        if (pose.isNullOrEmpty()) return
        val landmarks = pose.map { landmark ->
            CprLandmark(
                x = landmark.x().toDouble(),
                y = landmark.y().toDouble(),
                visibility = landmark.visibility().orElse(0f).toDouble(),
            )
        }
        val metrics = deriver.update(
            landmarks = landmarks,
            timestampMs = result.timestampMs(),
            frameWidth = uprightWidth,
            frameHeight = uprightHeight,
        ) ?: return
        onMetrics(
            metrics.toCprQualityMap() + mapOf(
                "camera_facing" to cameraFacing.eventValue,
                "camera_mount" to cameraMount.eventValue,
                "mirrored" to mirrored,
            ),
        )
    }

    private fun processingOptionsFor(rotationDegrees: Int): ImageProcessingOptions {
        val cached = cachedProcessingOptions
        if (cached != null && cachedRotationDegrees == rotationDegrees) return cached
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        cachedRotationDegrees = rotationDegrees
        cachedProcessingOptions = options
        return options
    }

    private fun updateUprightDimensions(width: Int, height: Int, rotationDegrees: Int) {
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            uprightWidth = height
            uprightHeight = width
        } else {
            uprightWidth = width
            uprightHeight = height
        }
    }

    private fun assetExists(assetName: String): Boolean =
        runCatching {
            appContext.assets.open(assetName).use { }
        }.isSuccess

    private fun reportUnavailable(message: String) {
        if (unavailableReported) return
        unavailableReported = true
        onUnavailable(message)
    }

    private companion object {
        const val MODEL_ASSET_NAME = "pose_landmarker_lite.task"
        const val NANOS_PER_MILLI = 1_000_000L
        const val STALL_RESET_MS = 1_500L
        const val TAG = "CprVisionAnalyzer"
    }
}
