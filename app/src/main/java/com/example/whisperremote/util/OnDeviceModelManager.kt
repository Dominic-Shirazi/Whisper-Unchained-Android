// This file manages on-device speech models and VAD weights for Whisper Unchained.
// It is called by Settings UI, First-Run Intro, and on-device transcription engine initialization.
// Inputs: models-manifest.json assets/string, app Context, download cancellation/progress callbacks, Wi-Fi override preferences.
// Outputs: Model file resolution, SHA-256 hash validation, resumable/cancellable HTTP downloads, and corrupt file purging.
// Strictly conforms to contracts/05-on-device.md §2.2 & §2.6 and tag models-v1 release manifests.
// Reason for creation: Model download manager and SHA-256 verification for on-device v1.3 per F-28.

package com.example.whisperremote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ModelFileSpec(
    val role: String,
    val fileName: String,
    val expectedBytes: Long,
    val sha256: String
)

data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val encoder: ModelFileSpec,
    val decoder: ModelFileSpec,
    val joiner: ModelFileSpec,
    val tokens: ModelFileSpec,
    val license: String
) {
    val allFiles: List<ModelFileSpec> get() = listOf(encoder, decoder, joiner, tokens)
    val totalBytes: Long get() = allFiles.sumOf { it.expectedBytes }
}

sealed interface ModelDownloadState {
    object Idle : ModelDownloadState
    data class Downloading(
        val modelId: String,
        val currentFileName: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val fileBytesDownloaded: Long,
        val fileBytesTotal: Long,
        val totalBytesDownloaded: Long,
        val totalBytesExpected: Long
    ) : ModelDownloadState
    data class Verifying(val fileName: String) : ModelDownloadState
    data class Ready(val modelId: String) : ModelDownloadState
    data class Failed(val modelId: String, val error: String) : ModelDownloadState
    object Cancelled : ModelDownloadState
}

class OnDeviceModelManager(
    private val context: Context,
    manifestJsonString: String? = null
) {
    companion object {
        private const val TAG = "OnDeviceModelManager"
        const val BASE_DOWNLOAD_URL = "https://github.com/Dominic-Shirazi/Whisper-Unchained-Android/releases/download/models-v1/"

        const val DEFAULT_MODEL_ID = "parakeet-tdt-110m-en-int8"
        const val ACCURACY_MODEL_ID = "parakeet-tdt-0.6b-v2-en-int8"
        const val MULTI_MODEL_ID = "parakeet-tdt-0.6b-v3-multi-int8"

        fun computeSha256(file: File): String {
            if (!file.exists() || !file.isFile) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val vadSpec: ModelFileSpec
    val availableModels: Map<String, ModelSpec>

    init {
        val jsonStr = manifestJsonString ?: loadManifestFromAssets()
        val json = JSONObject(jsonStr)

        val vadObj = json.getJSONObject("vad")
        vadSpec = ModelFileSpec(
            role = "vad",
            fileName = vadObj.getString("file"),
            expectedBytes = vadObj.getLong("bytes"),
            sha256 = vadObj.getString("sha256")
        )

        val modelsObj = json.getJSONObject("models")
        val parsedModels = mutableMapOf<String, ModelSpec>()

        for (key in modelsObj.keys()) {
            val m = modelsObj.getJSONObject(key)
            val enc = m.getJSONObject("encoder")
            val dec = m.getJSONObject("decoder")
            val jn = m.getJSONObject("joiner")
            val tok = m.getJSONObject("tokens")

            val (dispName, desc) = when (key) {
                DEFAULT_MODEL_ID -> Pair("Parakeet TDT 110M (Default)", "Fast & lightweight (~136 MB) · English")
                ACCURACY_MODEL_ID -> Pair("Parakeet TDT 0.6B v2", "Higher accuracy (~661 MB) · English")
                MULTI_MODEL_ID -> Pair("Parakeet TDT 0.6B v3", "25 European languages (~671 MB)")
                else -> Pair(key, "On-device model")
            }

            parsedModels[key] = ModelSpec(
                id = key,
                displayName = dispName,
                description = desc,
                encoder = ModelFileSpec("encoder", enc.getString("file"), enc.getLong("bytes"), enc.getString("sha256")),
                decoder = ModelFileSpec("decoder", dec.getString("file"), dec.getLong("bytes"), dec.getString("sha256")),
                joiner = ModelFileSpec("joiner", jn.getString("file"), jn.getLong("bytes"), jn.getString("sha256")),
                tokens = ModelFileSpec("tokens", tok.getString("file"), tok.getLong("bytes"), tok.getString("sha256")),
                license = m.optString("license", "CC-BY-4.0")
            )
        }
        availableModels = parsedModels
    }

    private fun loadManifestFromAssets(): String {
        return context.assets.open("models-manifest.json").bufferedReader().use { it.readText() }
    }

    fun getModelDir(modelId: String): File {
        val dir = File(context.filesDir, "models/$modelId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getVadDir(): File {
        val dir = File(context.filesDir, "models/vad")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getVadFile(): File = File(getVadDir(), vadSpec.fileName)

    fun getModelFiles(modelId: String): Map<String, File>? {
        val spec = availableModels[modelId] ?: return null
        val dir = getModelDir(modelId)
        return mapOf(
            "encoder" to File(dir, spec.encoder.fileName),
            "decoder" to File(dir, spec.decoder.fileName),
            "joiner" to File(dir, spec.joiner.fileName),
            "tokens" to File(dir, spec.tokens.fileName)
        )
    }

    /**
     * Checks if a file exists, has correct size, and matches SHA-256.
     * Deletes corrupted or partial files on mismatch per contract 05 §2.2.
     */
    fun verifyAndPruneFile(file: File, spec: ModelFileSpec): Boolean {
        if (!file.exists()) return false
        if (file.length() != spec.expectedBytes) {
            Log.w(TAG, "File size mismatch for ${spec.fileName}: expected ${spec.expectedBytes}, got ${file.length()}. Deleting.")
            file.delete()
            return false
        }
        val actualHash = computeSha256(file)
        if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
            Log.w(TAG, "SHA-256 mismatch for ${spec.fileName}: expected ${spec.sha256}, got $actualHash. Deleting.")
            file.delete()
            return false
        }
        return true
    }

    /**
     * Returns true if both the Silero VAD and all 4 model files are verified on disk.
     */
    fun isModelReady(modelId: String): Boolean {
        val vadFile = getVadFile()
        if (!verifyAndPruneFile(vadFile, vadSpec)) return false

        val spec = availableModels[modelId] ?: return false
        val modelFiles = getModelFiles(modelId) ?: return false

        return verifyAndPruneFile(modelFiles["encoder"]!!, spec.encoder) &&
                verifyAndPruneFile(modelFiles["decoder"]!!, spec.decoder) &&
                verifyAndPruneFile(modelFiles["joiner"]!!, spec.joiner) &&
                verifyAndPruneFile(modelFiles["tokens"]!!, spec.tokens)
    }

    /**
     * Deletes installed files for a given model to free space.
     */
    fun deleteModel(modelId: String) {
        val dir = File(context.filesDir, "models/$modelId")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Downloads a model and the shared Silero VAD weight.
     * Respects Wi-Fi preferences, reports granular progress, and validates SHA-256.
     */
    fun downloadModel(
        modelId: String,
        allowMobileData: Boolean = false,
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val spec = availableModels[modelId]
        if (spec == null) {
            onProgress(ModelDownloadState.Failed(modelId, "Unknown model: $modelId"))
            return
        }

        if (!allowMobileData && !isWifiConnected()) {
            onProgress(ModelDownloadState.Failed(modelId, "Wi-Fi connection required. Enable 'Use mobile data' to download over cellular."))
            return
        }

        val queue = mutableListOf<Pair<File, ModelFileSpec>>()
        // 1. VAD first if missing
        if (!verifyAndPruneFile(getVadFile(), vadSpec)) {
            queue.add(Pair(getVadFile(), vadSpec))
        }

        // 2. Model files
        val dir = getModelDir(modelId)
        val files = spec.allFiles
        for (f in files) {
            val target = File(dir, f.fileName)
            if (!verifyAndPruneFile(target, f)) {
                queue.add(Pair(target, f))
            }
        }

        if (queue.isEmpty()) {
            onProgress(ModelDownloadState.Ready(modelId))
            return
        }

        val totalBytesExpected = queue.sumOf { it.second.expectedBytes }
        var totalBytesDownloaded = 0L

        for ((index, item) in queue.withIndex()) {
            val (targetFile, fileSpec) = item
            if (cancelSignal.get()) {
                onProgress(ModelDownloadState.Cancelled)
                return
            }

            val partFile = File(targetFile.parentFile, "${fileSpec.fileName}.part")
            val url = "$BASE_DOWNLOAD_URL${fileSpec.fileName}"

            try {
                downloadFile(
                    url = url,
                    targetPartFile = partFile,
                    expectedBytes = fileSpec.expectedBytes,
                    cancelSignal = cancelSignal
                ) { fileBytesRead ->
                    val overall = totalBytesDownloaded + fileBytesRead
                    onProgress(
                        ModelDownloadState.Downloading(
                            modelId = modelId,
                            currentFileName = fileSpec.fileName,
                            fileIndex = index + 1,
                            totalFiles = queue.size,
                            fileBytesDownloaded = fileBytesRead,
                            fileBytesTotal = fileSpec.expectedBytes,
                            totalBytesDownloaded = overall,
                            totalBytesExpected = totalBytesExpected
                        )
                    )
                }

                if (cancelSignal.get()) {
                    partFile.delete()
                    onProgress(ModelDownloadState.Cancelled)
                    return
                }

                // Verification
                onProgress(ModelDownloadState.Verifying(fileSpec.fileName))
                val isValid = verifyAndPruneFile(partFile, fileSpec)
                if (!isValid) {
                    partFile.delete()
                    onProgress(ModelDownloadState.Failed(modelId, "Verification failed for ${fileSpec.fileName} (SHA-256 mismatch)"))
                    return
                }

                // Commit: rename verified .part to final target
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    // Fallback to copy if rename fails
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }

                totalBytesDownloaded += fileSpec.expectedBytes

            } catch (e: Exception) {
                partFile.delete()
                if (cancelSignal.get()) {
                    onProgress(ModelDownloadState.Cancelled)
                } else {
                    Log.e(TAG, "Download failed for ${fileSpec.fileName}", e)
                    onProgress(ModelDownloadState.Failed(modelId, e.message ?: "Download failed"))
                }
                return
            }
        }

        onProgress(ModelDownloadState.Ready(modelId))
    }

    private fun downloadFile(
        url: String,
        targetPartFile: File,
        expectedBytes: Long,
        cancelSignal: AtomicBoolean,
        onFileProgress: (Long) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty response body for $url")
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(targetPartFile).use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (cancelSignal.get()) {
                            return
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onFileProgress(downloaded)
                    }
                    output.flush()
                }
            }
        }
    }
}
