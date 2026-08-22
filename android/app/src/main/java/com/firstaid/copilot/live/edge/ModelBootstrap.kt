package com.firstaid.copilot.live.edge

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val BUNDLE_MANIFEST_ASSET = "models/apk_model_bundle.json"
private const val GEMMA_PARTS_MANIFEST_ASSET = "models/gemma/gemma-4-E2B-it.litertlm.parts.json"
private const val DEPLOY_MARKER = ".deploy_version"
private const val COPY_BUFFER_BYTES = 1024 * 1024

data class ModelBootstrapProgress(
    val phase: String,
    val label: String,
    val bytesDone: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDone.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val percent: Int
        get() = (fraction * 100f).toInt().coerceIn(0, 100)
}

data class ModelBootstrapResult(
    val deployed: Boolean,
    val skipped: Boolean,
    val root: File?,
    val detail: String,
)

object ModelBootstrap {
    suspend fun ensureDeployed(
        context: Context,
        onProgress: suspend (ModelBootstrapProgress) -> Unit = {},
    ): ModelBootstrapResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val assets = appContext.assets
        val bundle = readBundleManifest(assets)
        val gemma = readGemmaPartsManifest(assets)

        if (bundle == null && gemma == null) {
            return@withContext ModelBootstrapResult(
                deployed = false,
                skipped = true,
                root = null,
                detail = "no bundled APK models",
            )
        }

        val targetRoot = defaultEdgeModelRoots(appContext).firstOrNull()
            ?: appContext.getExternalFilesDir(null)?.resolve("models")
            ?: File(appContext.filesDir, "models")
        targetRoot.mkdirs()

        val deployVersion = bundle?.deployVersion ?: "offline-apk-v1"
        if (isAlreadyDeployed(targetRoot, deployVersion, bundle, gemma)) {
            return@withContext ModelBootstrapResult(
                deployed = false,
                skipped = true,
                root = targetRoot,
                detail = "models already deployed",
            )
        }

        val totalBytes = (bundle?.entries?.sumOf { it.sizeBytes } ?: 0L) + (gemma?.totalSizeBytes ?: 0L)
        var bytesDone = 0L

        suspend fun publish(phase: String, label: String) {
            withContext(Dispatchers.Main.immediate) {
                onProgress(ModelBootstrapProgress(phase, label, bytesDone, totalBytes))
            }
        }

        publish("prepare", "准备部署离线 AI 模型")

        if (gemma != null) {
            val target = targetRoot.resolve(gemma.target)
            if (target.isFile && target.length() == gemma.totalSizeBytes) {
                bytesDone += gemma.totalSizeBytes
                publish("gemma", "Gemma 模型已存在")
            } else {
                mergeGemmaParts(
                    assets = assets,
                    manifest = gemma,
                    target = target,
                    onCopied = { copied ->
                        bytesDone += copied
                    },
                    onProgress = {
                        publish("gemma", "正在部署 Gemma 模型")
                    },
                )
            }
        }

        bundle?.entries.orEmpty().forEach { entry ->
            val target = targetRoot.resolve(entry.target)
            if (target.isFile && target.length() == entry.sizeBytes) {
                bytesDone += entry.sizeBytes
                publish("copy", "已存在 ${entry.target}")
            } else {
                copyAssetToFile(
                    assets = assets,
                    assetPath = entry.asset,
                    target = target,
                    expectedBytes = entry.sizeBytes,
                    onCopied = { copied ->
                        bytesDone += copied
                    },
                    onProgress = {
                        publish("copy", "正在部署 ${entry.target}")
                    },
                )
            }
        }

        targetRoot.resolve(DEPLOY_MARKER).writeText(deployVersion)
        publish("done", "离线模型部署完成")

        ModelBootstrapResult(
            deployed = true,
            skipped = false,
            root = targetRoot,
            detail = "models deployed",
        )
    }

    private fun readBundleManifest(assets: AssetManager): ModelBundleManifest? =
        runCatching {
            val json = assets.open(BUNDLE_MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = JSONObject(json)
            val entries = obj.optJSONArray("entries").toModelAssetEntries()
            ModelBundleManifest(
                deployVersion = obj.optString("deployVersion", "offline-apk-v1"),
                entries = entries,
            )
        }.getOrNull()

    private fun readGemmaPartsManifest(assets: AssetManager): GemmaPartsManifest? =
        runCatching {
            val json = assets.open(GEMMA_PARTS_MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = JSONObject(json)
            val parts = obj.optJSONArray("parts").toGemmaParts()
            if (parts.isEmpty()) return@runCatching null
            GemmaPartsManifest(
                fileName = obj.getString("fileName"),
                target = obj.optString("target", "gemma/${obj.getString("fileName")}"),
                totalSizeBytes = obj.getLong("totalSizeBytes"),
                parts = parts,
            )
        }.getOrNull()

    private fun isAlreadyDeployed(
        root: File,
        deployVersion: String,
        bundle: ModelBundleManifest?,
        gemma: GemmaPartsManifest?,
    ): Boolean {
        if (root.resolve(DEPLOY_MARKER).readTextOrNull() != deployVersion) return false
        if (gemma != null) {
            val target = root.resolve(gemma.target)
            if (!target.isFile || target.length() != gemma.totalSizeBytes) return false
        }
        bundle?.entries.orEmpty().forEach { entry ->
            val target = root.resolve(entry.target)
            if (!target.isFile || target.length() != entry.sizeBytes) return false
        }
        return true
    }

    private suspend fun mergeGemmaParts(
        assets: AssetManager,
        manifest: GemmaPartsManifest,
        target: File,
        onCopied: (Long) -> Unit,
        onProgress: suspend () -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        if (tmp.exists()) tmp.delete()

        FileOutputStream(tmp).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            manifest.parts.forEach { part ->
                assets.open(part.asset).use { input ->
                    var partWritten = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        partWritten += read.toLong()
                        onCopied(read.toLong())
                        onProgress()
                    }
                    require(partWritten == part.sizeBytes) {
                        "Gemma shard size mismatch: ${part.file} expected=${part.sizeBytes} actual=$partWritten"
                    }
                }
            }
        }

        require(tmp.length() == manifest.totalSizeBytes) {
            "Gemma size mismatch: expected=${manifest.totalSizeBytes} actual=${tmp.length()}"
        }
        if (target.exists()) target.delete()
        require(tmp.renameTo(target)) {
            "Unable to move Gemma model into place: ${target.absolutePath}"
        }
    }

    private suspend fun copyAssetToFile(
        assets: AssetManager,
        assetPath: String,
        target: File,
        expectedBytes: Long,
        onCopied: (Long) -> Unit,
        onProgress: suspend () -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        if (tmp.exists()) tmp.delete()

        assets.open(assetPath).use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    onCopied(read.toLong())
                    onProgress()
                }
            }
        }

        require(tmp.length() == expectedBytes) {
            "Asset size mismatch: $assetPath expected=$expectedBytes actual=${tmp.length()}"
        }
        if (target.exists()) target.delete()
        require(tmp.renameTo(target)) {
            "Unable to move asset into place: ${target.absolutePath}"
        }
    }
}

private data class ModelBundleManifest(
    val deployVersion: String,
    val entries: List<ModelAssetEntry>,
)

private data class ModelAssetEntry(
    val asset: String,
    val target: String,
    val sizeBytes: Long,
)

private data class GemmaPartsManifest(
    val fileName: String,
    val target: String,
    val totalSizeBytes: Long,
    val parts: List<GemmaPart>,
)

private data class GemmaPart(
    val file: String,
    val asset: String,
    val sizeBytes: Long,
)

private fun JSONArray?.toModelAssetEntries(): List<ModelAssetEntry> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val obj = optJSONObject(index) ?: continue
            add(
                ModelAssetEntry(
                    asset = obj.getString("asset"),
                    target = obj.getString("target"),
                    sizeBytes = obj.getLong("sizeBytes"),
                ),
            )
        }
    }
}

private fun JSONArray?.toGemmaParts(): List<GemmaPart> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val obj = optJSONObject(index) ?: continue
            add(
                GemmaPart(
                    file = obj.getString("file"),
                    asset = obj.getString("asset"),
                    sizeBytes = obj.getLong("sizeBytes"),
                ),
            )
        }
    }
}

private fun File.readTextOrNull(): String? =
    runCatching {
        if (isFile) readText().trim() else null
    }.getOrNull()
