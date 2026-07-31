package com.firstaid.copilot.live.ui

import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.firstaid.copilot.live.ui.theme.FirstAidColors
import com.firstaid.copilot.live.ui.theme.FirstAidDimens
import com.firstaid.copilot.live.ui.theme.FirstAidTheme
import com.firstaid.copilot.live.ui.theme.FirstAidType
import com.firstaid.copilot.live.vision.cpr.CprOverlayPoint
import com.firstaid.copilot.live.vision.cpr.CprVisionAnalyzer
import com.firstaid.copilot.live.vision.cpr.CprVisionOverlaySnapshot
import com.firstaid.copilot.live.vision.cpr.VisionCameraFacing
import com.firstaid.copilot.live.vision.cpr.VisionCameraMount
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun CameraPiPWindow(
    visible: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    enableVisionAnalysis: Boolean = false,
    visionOverlay: CprVisionOverlaySnapshot? = null,
    onVisionMetrics: (Map<String, Any?>) -> Unit = {},
    onVisionUnavailable: (String) -> Unit = {},
) {
    if (!visible) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isInspectionMode = LocalInspectionMode.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val latestOnVisionMetrics by rememberUpdatedState(onVisionMetrics)
    val latestOnVisionUnavailable by rememberUpdatedState(onVisionUnavailable)
    val shape = RoundedCornerShape(FirstAidDimens.CardRadius)
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var visionAnalyzer by remember { mutableStateOf<CprVisionAnalyzer?>(null) }
    var analysisExecutor by remember { mutableStateOf<ExecutorService?>(null) }
    var bindingFailed by remember { mutableStateOf(false) }
    val showCameraPreview = hasCameraPermission && !bindingFailed && !isInspectionMode
    val shouldAnalyzeVision = enableVisionAnalysis && hasCameraPermission && !isInspectionMode

    fun releaseCameraBinding() {
        visionAnalyzer?.close()
        visionAnalyzer = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = dragOffsetX.roundToInt(),
                    y = dragOffsetY.roundToInt(),
                )
            }
            .size(width = PipWindowWidth, height = PipWindowHeight)
            .background(FirstAidColors.Surface, shape)
            .border(
                width = PipBorderWidth,
                color = FirstAidColors.SurfaceVariant,
                shape = shape,
            )
            .clip(shape)
            .pointerDrag {
                dragOffsetX += it.x
                dragOffsetY += it.y
            },
    ) {
        if (showCameraPreview) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                    }
                },
                update = { previewView = it },
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {
                        contentDescription = "相机采集预览"
                    },
            )
        } else {
            CameraPlaceholder(
                showPermissionButton = !hasCameraPermission,
                onRequestPermission = onRequestPermission,
                message = if (hasCameraPermission) "相机预览不可用" else "需要相机权限",
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showCameraPreview && visionOverlay != null) {
            CprPipVisionOverlay(
                overlay = visionOverlay,
                modifier = Modifier.fillMaxSize(),
            )
        }

        CameraCaptureLabel(
            analyzing = shouldAnalyzeVision && showCameraPreview,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(FirstAidDimens.TightGap),
        )
        CameraCloseButton(
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(FirstAidDimens.TightGap / OverlayInsetDivisor),
        )
    }

    LaunchedEffect(
        context,
        lifecycleOwner,
        previewView,
        hasCameraPermission,
        lensFacing,
        enableVisionAnalysis,
        isInspectionMode,
    ) {
        val view = previewView
        if (!hasCameraPermission || view == null || isInspectionMode) {
            releaseCameraBinding()
            return@LaunchedEffect
        }

        bindingFailed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    releaseCameraBinding()
                    provider.unbindAll()
                    // Pin the preview to 4:3 so its aspect matches the analysis
                    // stream; the overlay's FILL_CENTER compensation assumes both
                    // share the same source aspect ratio.
                    val preview = CameraPreview.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build(),
                        )
                        .build()
                        .also {
                            it.setSurfaceProvider(view.surfaceProvider)
                        }
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    val analysis = if (shouldAnalyzeVision) {
                        val executor = Executors.newSingleThreadExecutor()
                        val visionCameraFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            VisionCameraFacing.Front
                        } else {
                            VisionCameraFacing.Back
                        }
                        val analyzer = CprVisionAnalyzer(
                            context = context,
                            onMetrics = { metrics ->
                                mainHandler.post { latestOnVisionMetrics(metrics) }
                            },
                            onUnavailable = { message ->
                                mainHandler.post { latestOnVisionUnavailable(message) }
                            },
                            cameraFacing = visionCameraFacing,
                            cameraMount = VisionCameraMount.SideFixed,
                            mirrored = visionCameraFacing == VisionCameraFacing.Front,
                        )
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            // RGBA output makes ImageProxy.toBitmap() a near-trivial
                            // copy (no YUV->JPEG->decode); 4:3 @ ~VGA keeps the frame
                            // small for low-latency conversion while MediaPipe lite
                            // downsamples internally anyway.
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(640, 480),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                        ),
                                    )
                                    .build(),
                            )
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(executor, analyzer)
                                visionAnalyzer = analyzer
                                analysisExecutor = executor
                            }
                    } else {
                        null
                    }

                    if (analysis != null) {
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                    } else {
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    }
                    cameraProvider = provider
                    bindingFailed = false
                }.onFailure {
                    releaseCameraBinding()
                    bindingFailed = true
                    val message = "相机预览或识别启动失败：${it.message ?: it.javaClass.simpleName}"
                    mainHandler.post { latestOnVisionUnavailable(message) }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            releaseCameraBinding()
        }
    }
}

private fun Modifier.pointerDrag(onDrag: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        detectDragGestures { _, dragAmount ->
            onDrag(dragAmount)
        }
    }

@Composable
private fun CameraPlaceholder(
    showPermissionButton: Boolean,
    onRequestPermission: () -> Unit,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(FirstAidColors.SurfaceVariant)
            .semantics {
                contentDescription = "相机采集预览，$message"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FirstAidDimens.TightGap),
            modifier = Modifier.padding(FirstAidDimens.ItemGap),
        ) {
            Text(
                text = message,
                style = FirstAidType.Label,
                color = FirstAidColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            if (showPermissionButton) {
                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(FirstAidDimens.ButtonRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FirstAidColors.Progress,
                        contentColor = FirstAidColors.OnAccent,
                    ),
                    contentPadding = PaddingValues(
                        horizontal = FirstAidDimens.ItemGap,
                        vertical = FirstAidDimens.TightGap / ButtonVerticalPaddingDivisor,
                    ),
                    modifier = Modifier
                        .height(FirstAidDimens.MinTouch)
                        .semantics {
                            contentDescription = "开启相机权限"
                            role = Role.Button
                        },
                ) {
                    Text(
                        text = "开启相机",
                        style = FirstAidType.Label,
                        color = FirstAidColors.OnAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraCaptureLabel(
    analyzing: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (analyzing) {
                "相机状态：识别中"
            } else {
                "相机状态：仅采集"
            }
        },
        shape = RoundedCornerShape(FirstAidDimens.ChipRadius),
        color = FirstAidColors.ScrimSoft,
    ) {
        Text(
            text = if (analyzing) "识别中" else "仅采集",
            style = FirstAidType.Label,
            color = FirstAidColors.TextSecondary,
            modifier = Modifier.padding(
                horizontal = FirstAidDimens.TightGap,
                vertical = FirstAidDimens.TightGap / LabelVerticalPaddingDivisor,
            ),
        )
    }
}

@Composable
private fun CameraCloseButton(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(FirstAidDimens.MinTouch)
            .clip(CircleShape)
            .clickable(onClick = onClose)
            .semantics {
                contentDescription = "关闭相机小窗"
                role = Role.Button
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            modifier = Modifier
                .size(FirstAidDimens.TightGap * CloseButtonSizeMultiplier)
                .clip(CircleShape),
            shape = CircleShape,
            color = FirstAidColors.ScrimSoft,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "×",
                    style = FirstAidType.Title,
                    color = FirstAidColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CprPipVisionOverlay(
    overlay: CprVisionOverlaySnapshot,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // Map normalized landmark coords (in the upright source frame) into view
        // pixels the same way PreviewView FILL_CENTER does: scale to cover, keep
        // aspect ratio, center-crop the overflow. Without this the skeleton drifts
        // off the body because the preview is cropped but the overlay wasn't.
        val viewW = size.width
        val viewH = size.height
        val srcW = overlay.sourceWidth.toFloat()
        val srcH = overlay.sourceHeight.toFloat()
        val hasSource = srcW > 0f && srcH > 0f
        val coverScale = if (hasSource) maxOf(viewW / srcW, viewH / srcH) else 1f
        val contentW = if (hasSource) srcW * coverScale else viewW
        val contentH = if (hasSource) srcH * coverScale else viewH
        val offsetX = (viewW - contentW) / 2f
        val offsetY = (viewH - contentH) / 2f

        fun CprOverlayPoint.toOffset(): Offset =
            Offset(
                x = offsetX + x.coerceIn(0.0, 1.0).toFloat() * contentW,
                y = offsetY + y.coerceIn(0.0, 1.0).toFloat() * contentH,
            )

        fun bone(a: CprOverlayPoint?, b: CprOverlayPoint?, color: Color) {
            if (a == null || b == null) return
            drawLine(
                color = color.copy(alpha = 0.9f),
                start = a.toOffset(),
                end = b.toOffset(),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }

        fun joint(point: CprOverlayPoint?, color: Color, radius: Float) {
            if (point == null) return
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = radius + 2f,
                center = point.toOffset(),
            )
            drawCircle(color = color, radius = radius, center = point.toOffset())
        }

        bone(overlay.leftShoulder, overlay.leftElbow, Color(0xFF38BDF8))
        bone(overlay.leftElbow, overlay.leftWrist, Color(0xFF38BDF8))
        bone(overlay.rightShoulder, overlay.rightElbow, Color(0xFF60A5FA))
        bone(overlay.rightElbow, overlay.rightWrist, Color(0xFF60A5FA))
        bone(overlay.leftShoulder, overlay.rightShoulder, Color(0xFFA5B4FC))

        val handCenter = overlay.handCenter
        val chestCenter = overlay.chestCenter
        val alignment = if (handCenter != null && chestCenter != null) {
            hypot(handCenter.x - chestCenter.x, handCenter.y - chestCenter.y)
        } else {
            Double.POSITIVE_INFINITY
        }
        val alignColor = if (alignment <= 0.07) Color(0xFF22C55E) else Color(0xFFFBBF24)

        if (handCenter != null && chestCenter != null) {
            drawLine(
                color = alignColor.copy(alpha = 0.85f),
                start = handCenter.toOffset(),
                end = chestCenter.toOffset(),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }

        joint(overlay.leftShoulder, Color(0xFFA5B4FC), 4f)
        joint(overlay.rightShoulder, Color(0xFFA5B4FC), 4f)
        joint(overlay.leftElbow, Color(0xFF38BDF8), 5f)
        joint(overlay.rightElbow, Color(0xFF60A5FA), 5f)
        joint(overlay.leftWrist, Color(0xFF22D3EE), 4f)
        joint(overlay.rightWrist, Color(0xFF22D3EE), 4f)

        chestCenter?.let { chest ->
            val c = chest.toOffset()
            drawCircle(
                color = Color(0xFFEF4444).copy(alpha = 0.3f),
                radius = 16f,
                center = c,
                style = Stroke(width = 3f),
            )
            drawLine(
                color = Color(0xFFEF4444),
                start = Offset(c.x - 10f, c.y),
                end = Offset(c.x + 10f, c.y),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFEF4444),
                start = Offset(c.x, c.y - 10f),
                end = Offset(c.x, c.y + 10f),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }

        joint(overlay.handCenter, alignColor, 6f)
    }
}

@Preview(showBackground = true)
@Composable
private fun CameraPiPWindowPreview() {
    FirstAidTheme {
        Box(
            modifier = Modifier
                .background(FirstAidColors.Background)
                .padding(FirstAidDimens.ScreenPadding),
        ) {
            CameraPiPWindow(
                visible = true,
                hasCameraPermission = false,
                onRequestPermission = {},
                onClose = {},
            )
        }
    }
}

private val PipWindowWidth = 120.dp
private val PipWindowHeight = 180.dp
private val PipBorderWidth = 1.dp
private const val ButtonVerticalPaddingDivisor = 2f
private const val CloseButtonSizeMultiplier = 4f
private const val LabelVerticalPaddingDivisor = 2f
private const val OverlayInsetDivisor = 2f
