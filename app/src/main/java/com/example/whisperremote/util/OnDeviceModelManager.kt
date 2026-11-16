// This file manages on-device speech models and VAD weights for Whisper Unchained.
// It is called by Settings UI, First-Run Intro, and on-device transcription engine initialization.
// Inputs: models-manifest.json assets/string, app Context, download cancellation/progress callbacks, Wi-Fi override preferences.
// Outputs: Model file resolution, SHA-256 hash validation, resumable/cancellable HTTP downloads, and corrupt file purging.
// Strictly conforms to contracts/05-on-device.md §2.2 & §2.6 and tag models-v1 release manifests.
// Reason for edit: Update models to unified-en recommended, 110M small, and v3-multi languages; purge v2 on update per F-52 revised & F-57.

package com.example.whisperremote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.whisperremote.data.SettingsRepository
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
    val license: String,
    val licenseFile: String? = null,
    val noticeFile: String? = null,
    val notice: String? = null
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
    manifestJsonString: String? = null,
    val baseUrl: String = BASE_DOWNLOAD_URL
) {
    companion object {
        private const val TAG = "OnDeviceModelManager"
        const val BASE_DOWNLOAD_URL = "https://github.com/Dominic-Shirazi/Whisper-Unchained-Android/releases/download/models-v1/"

        const val RECOMMENDED_MODEL_ID = "parakeet-unified-en-0.6b-int8"
        const val DEFAULT_MODEL_ID = "parakeet-tdt-110m-en-int8"
        const val MULTI_MODEL_ID = "parakeet-tdt-0.6b-v3-multi-int8"
        const val REMOVED_V2_MODEL_ID = "parakeet-tdt-0.6b-v2-en-int8"
        @Deprecated("Use RECOMMENDED_MODEL_ID or MULTI_MODEL_ID", ReplaceWith("RECOMMENDED_MODEL_ID"))
        const val ACCURACY_MODEL_ID = RECOMMENDED_MODEL_ID

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

        // Order preserved: recommended (unified), standard (110m), multi (v3) per contract 05 §2
        val orderedKeys = listOf(RECOMMENDED_MODEL_ID, DEFAULT_MODEL_ID, MULTI_MODEL_ID)
        val allKeys = orderedKeys.filter { modelsObj.has(it) } + modelsObj.keys().asSequence().filter { !orderedKeys.contains(it) }.toList()

        for (key in allKeys) {
            val m = modelsObj.getJSONObject(key)
            val enc = m.getJSONObject("encoder")
            val dec = m.getJSONObject("decoder")
            val jn = m.getJSONObject("joiner")
            val tok = m.getJSONObject("tokens")

            val (dispName, desc) = when (key) {
                RECOMMENDED_MODEL_ID -> Pair("Recommended · most accurate (633 MB)", "Parakeet unified-en 0.6B")
                DEFAULT_MODEL_ID -> Pair("Small and fast (135 MB)", "Parakeet TDT 110M")
                MULTI_MODEL_ID -> Pair("Other languages (671 MB)", "For English, the recommended model is more accurate.")
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
                license = m.optString("license", "CC-BY-4.0"),
                licenseFile = if (m.has("license_file")) m.getString("license_file") else null,
                noticeFile = if (m.has("notice_file")) m.getString("notice_file") else null,
                notice = if (m.has("notice")) m.getString("notice") else null
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
            Log.w(TAG, "File ${file.name} size mismatch: got ${file.length()}, expected ${spec.expectedBytes}. Deleting.")
            file.delete()
            return false
        }
        val actualSha256 = computeSha256(file)
        if (!actualSha256.equals(spec.sha256, ignoreCase = true)) {
            Log.w(TAG, "File ${file.name} SHA-256 mismatch: got $actualSha256, expected ${spec.sha256}. Deleting.")
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
        if (!verifyAndPruneFile(vadFile, vadSpec)) {
            return false
        }

        val spec = availableModels[modelId] ?: return false
        val files = getModelFiles(modelId) ?: return false

        return verifyAndPruneFile(files["encoder"]!!, spec.encoder) &&
                verifyAndPruneFile(files["decoder"]!!, spec.decoder) &&
                verifyAndPruneFile(files["joiner"]!!, spec.joiner) &&
                verifyAndPruneFile(files["tokens"]!!, spec.tokens)
    }

    /**
     * Deletes model files from disk.
     */
    fun deleteModel(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        return dir.deleteRecursively()
    }

    /**
     * Migrates removed models on update per contract 05 §2 item 2 and F-52 revised.
     * Deletes files for parakeet-tdt-0.6b-v2-en-int8. If it was selected, switches to 110M
     * and returns a notification notice for the user.
     */
    fun checkAndMigrateRemovedModels(settingsRepository: SettingsRepository): String? {
        val v2Dir = File(context.filesDir, "models/$REMOVED_V2_MODEL_ID")
        if (v2Dir.exists()) {
            v2Dir.deleteRecursively()
            Log.i(TAG, "Purged legacy v2 model files: ${v2Dir.absolutePath}")
        }

        if (settingsRepository.selectedOnDeviceModelId == REMOVED_V2_MODEL_ID) {
            settingsRepository.selectedOnDeviceModelId = DEFAULT_MODEL_ID
            return "That model was replaced. Using the small model. The recommended model is in Transcription settings."
        }
        return null
    }

    /**
     * Downloads a model and the shared Silero VAD weight.
     * Supports Wi-Fi check, HTTP Range resuming (.part files), cancellation, and hash verification.
     */
    fun downloadModel(
        modelId: String,
        allowMobileData: Boolean = false,
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
        onStateChange: (ModelDownloadState) -> Unit
    ) {
        val spec = availableModels[modelId]
        if (spec == null) {
            onStateChange(ModelDownloadState.Failed(modelId, "Unknown model ID: $modelId"))
            return
        }

        if (!canDownload(allowMobileData)) {
            onStateChange(ModelDownloadState.Failed(modelId, "Wi-Fi is required to download models. Toggle 'Use mobile data' to override."))
            return
        }

        val totalExpected = vadSpec.expectedBytes + spec.totalBytes
        var totalDownloaded = 0L

        try {
            // 1. VAD first if missing
            val vadFile = getVadFile()
            if (!verifyAndPruneFile(vadFile, vadSpec)) {
                downloadFileResumable(
                    url = baseUrl + vadSpec.fileName,
                    targetFile = vadFile,
                    expectedBytes = vadSpec.expectedBytes,
                    expectedSha256 = vadSpec.sha256,
                    cancelSignal = cancelSignal
                ) { fileBytes ->
                    onStateChange(
                        ModelDownloadState.Downloading(
                            modelId = modelId,
                            currentFileName = vadSpec.fileName,
                            fileIndex = 1,
                            totalFiles = 5,
                            fileBytesDownloaded = fileBytes,
                            fileBytesTotal = vadSpec.expectedBytes,
                            totalBytesDownloaded = totalDownloaded + fileBytes,
                            totalBytesExpected = totalExpected
                        )
                    )
                }
                if (cancelSignal.get()) {
                    onStateChange(ModelDownloadState.Cancelled)
                    return
                }
                onStateChange(ModelDownloadState.Verifying(vadSpec.fileName))
                if (!verifyAndPruneFile(vadFile, vadSpec)) {
                    onStateChange(ModelDownloadState.Failed(modelId, "Verification failed for ${vadSpec.fileName}"))
                    return
                }
            }
            totalDownloaded += vadSpec.expectedBytes

            // 2. Download all 4 model files
            val modelFiles = getModelFiles(modelId)!!
            val fileSpecs = listOf(
                Pair(spec.encoder, modelFiles["encoder"]!!),
                Pair(spec.decoder, modelFiles["decoder"]!!),
                Pair(spec.joiner, modelFiles["joiner"]!!),
                Pair(spec.tokens, modelFiles["tokens"]!!)
            )

            for ((index, pair) in fileSpecs.withIndex()) {
                val (fileSpec, targetFile) = pair
                if (!verifyAndPruneFile(targetFile, fileSpec)) {
                    downloadFileResumable(
                        url = baseUrl + fileSpec.fileName,
                        targetFile = targetFile,
                        expectedBytes = fileSpec.expectedBytes,
                        expectedSha256 = fileSpec.sha256,
                        cancelSignal = cancelSignal
                    ) { fileBytes ->
                        onStateChange(
                            ModelDownloadState.Downloading(
                                modelId = modelId,
                                currentFileName = fileSpec.fileName,
                                fileIndex = index + 2,
                                totalFiles = 5,
                                fileBytesDownloaded = fileBytes,
                                fileBytesTotal = fileSpec.expectedBytes,
                                totalBytesDownloaded = totalDownloaded + fileBytes,
                                totalBytesExpected = totalExpected
                            )
                        )
                    }
                    if (cancelSignal.get()) {
                        onStateChange(ModelDownloadState.Cancelled)
                        return
                    }
                    onStateChange(ModelDownloadState.Verifying(fileSpec.fileName))
                    if (!verifyAndPruneFile(targetFile, fileSpec)) {
                        onStateChange(ModelDownloadState.Failed(modelId, "Verification failed for ${fileSpec.fileName}"))
                        return
                    }
                }
                totalDownloaded += fileSpec.expectedBytes
            }

            onStateChange(ModelDownloadState.Ready(modelId))

        } catch (e: Exception) {
            Log.e(TAG, "Download error for model $modelId", e)
            if (cancelSignal.get()) {
                onStateChange(ModelDownloadState.Cancelled)
            } else {
                onStateChange(ModelDownloadState.Failed(modelId, e.message ?: "Download failed"))
            }
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun canDownload(allowMobileData: Boolean): Boolean {
        return allowMobileData || isWifiConnected()
    }

    private fun downloadFileResumable(
        url: String,
        targetFile: File,
        expectedBytes: Long,
        expectedSha256: String,
        cancelSignal: AtomicBoolean,
        onFileProgress: (Long) -> Unit
    ) {
        val targetPartFile = File(targetFile.parentFile, "${targetFile.name}.part")
        val existingBytes = if (targetPartFile.exists()) targetPartFile.length() else 0L

        // If part file is larger than expected, it's invalid so delete and start over
        val resumeFrom = if (existingBytes in 1 until expectedBytes) existingBytes else 0L
        if (resumeFrom == 0L && targetPartFile.exists()) {
            targetPartFile.delete()
        }

        val requestBuilder = Request.Builder().url(url)
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }
        val request = requestBuilder.build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty response body for $url")

            val isPartial = response.code == 206
            val appendMode = isPartial && resumeFrom > 0
            var downloaded = if (appendMode) resumeFrom else 0L

            onFileProgress(downloaded)

            body.byteStream().use { input ->
                FileOutputStream(targetPartFile, appendMode).use { output ->
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

        if (cancelSignal.get()) {
            return
        }

        // Rename .part to final file
        if (targetPartFile.exists() && targetPartFile.length() == expectedBytes) {
            if (targetFile.exists()) targetFile.delete()
            targetPartFile.renameTo(targetFile)
        }
    }
}
