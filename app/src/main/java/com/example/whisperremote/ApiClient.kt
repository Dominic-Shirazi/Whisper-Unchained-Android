// This file provides the HTTP API client for Whisper Unchained.
// It is called by FloatingButtonService (for audio transcription) and MainActivity (for connection & health checks).
// Inputs: audio File (WAV/M4A), optional prompt hints, API key, model name from SettingsRepository, and per-dictation StyleOverrides from FloatingButtonService.
// Outputs: TranscriptionResult (transcribed text or typed error), HealthResult, and model list.
// It speaks the OpenAI-compatible standard POST /v1/audio/transcriptions, GET /health, and GET /v1/models.

package com.example.whisperremote

// Reason for edit: Added cleanup form field and cleanup_always parsing from /health per contract K-10.
// Reason for edit: Gate transcription response body behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9.
// Reason for edit: Added per-dictation StyleOverrides support for style pill overrides per contract F-6 (contracts/01 §1 & contracts/02 §5).
// Reason for edit: Ensure all public callbacks dispatch on Looper.getMainLooper() to prevent background thread UI/Toast crashes per F-26.
// Reason for edit: Configure pinned TLS trust manager and hostname verifier per contract 06 §3 (F-38/F-39).
// Reason for edit: Scope certificate pinning strictly to paired PC origin per F-40.
// Reason for edit: Add modifyVocabulary, modifyNameCorrections, and replayPendingMutations per contract 01 §8 and F-43.
// Reason for edit: AtomicBoolean replay guard, OpenAI error.message parsing, and 404 cache recovery per F-44.

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.QueuedMutation
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.net.PinnedCertificateHelper
import com.example.whisperremote.util.CleanupOptions
import com.example.whisperremote.util.EditCommandProcessor
import com.example.whisperremote.util.EditCommandSplit
import com.example.whisperremote.util.TranscriptCleaner
import com.example.whisperremote.util.TranscriptSanitizer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class Empty(val message: String = "No speech detected") : TranscriptionResult()
    data class ServerPaused(val message: String, val reason: String? = null) : TranscriptionResult()
    data class ModelUnavailable(val retryAfterSeconds: Int) : TranscriptionResult()
    data class AuthError(val message: String) : TranscriptionResult()
    data class ServerError(val code: Int, val message: String) : TranscriptionResult()
    data class NetworkError(val error: Throwable) : TranscriptionResult()
}

// Reason for edit: Added StyleOverrides per contract F-6 & F-7 for per-dictation floating button style pill and sheet adjustments
data class StyleOverrides(
    val rewrite: String,
    val shorten: String,
    val punctuation: String,
    val tone: String = "neutral"
)

sealed class HealthResult {
    data class Ok(val model: String?, val authRequired: Boolean, val cleanupAlways: Boolean? = null) : HealthResult()
    data class Paused(val reason: String?, val model: String?, val authRequired: Boolean, val cleanupAlways: Boolean? = null) : HealthResult()
    data class ServiceError(val message: String) : HealthResult()
    data class Unreachable(val error: Throwable) : HealthResult()

    val isReachable: Boolean
        get() = this is Ok || this is Paused
}

data class VocabularyData(
    val vocabulary: List<String>,
    val fromNameCorrections: List<String>,
    val nameCorrections: Map<String, String> = emptyMap(),
    val chars: Int,
    val maxChars: Int,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList()
)

sealed class VocabularyResult {
    data class Success(val data: VocabularyData) : VocabularyResult()
    data class Error(val code: Int, val message: String) : VocabularyResult()
    data class NetworkError(val error: Throwable) : VocabularyResult()
}

// Reason for edit: Add NameCorrectionsResult per contract 01 §8 and F-43
sealed class NameCorrectionsResult {
    data class Success(
        val nameCorrections: Map<String, String>,
        val changed: Map<String, String> = emptyMap(),
        val removed: List<String> = emptyList()
    ) : NameCorrectionsResult()
    data class Unsupported(val message: String = "Update the PC app to edit these here") : NameCorrectionsResult()
    data class Error(val code: Int, val message: String) : NameCorrectionsResult()
    data class NetworkError(val error: Throwable) : NameCorrectionsResult()
}

class ApiClient(private val context: Context) {

    private val repository = SettingsRepository(context)
    private val isReplaying = AtomicBoolean(false)

    // Standard timeout client (60s) with system CA trust store for cloud endpoints & general web APIs
    private val publicClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Pinned client for the paired PC origin enforcing SHA-256 leaf cert pin per contract 06 §3 & F-40
    private val pcClient = PinnedCertificateHelper.configureClient(
        publicClient.newBuilder(),
        pinProvider = { repository.serverCertSha256 }
    ).build()

    // Fast client (4s) for health/vocabulary checks with standard CA trust store
    private val fastPublicClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Fast pinned client for the paired PC origin
    private val fastPcClient = PinnedCertificateHelper.configureClient(
        fastPublicClient.newBuilder(),
        pinProvider = { repository.serverCertSha256 }
    ).build()

    /**
     * Determines whether the given target URL matches the scheme, host, and port of the paired PC base URL.
     * Only true when a certificate pin is stored and origin matches exactly per F-40.
     */
    fun isPairedPcOrigin(url: String): Boolean {
        val pin = repository.serverCertSha256
        if (pin.isNullOrBlank()) return false
        val base = repository.baseUrl
        if (base.isBlank()) return false
        return try {
            val targetUri = URI(url)
            val baseUri = URI(base)
            val targetScheme = targetUri.scheme?.lowercase()
            val baseScheme = baseUri.scheme?.lowercase()
            if (targetScheme != baseScheme) return false
            val targetHost = targetUri.host.orEmpty()
            val baseHost = baseUri.host.orEmpty()
            if (!targetHost.equals(baseHost, ignoreCase = true)) return false
            val targetPort = if (targetUri.port != -1) targetUri.port else (if (targetScheme == "https") 443 else 80)
            val basePort = if (baseUri.port != -1) baseUri.port else (if (baseScheme == "https") 443 else 80)
            targetPort == basePort
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the pinned PC client if the target URL matches the paired PC origin and a pin is stored;
     * otherwise returns the unpinned public client using standard CA trust per contract 06 & F-40.
     */
    fun clientFor(url: String): OkHttpClient {
        return if (isPairedPcOrigin(url)) pcClient else publicClient
    }

    /**
     * Returns the fast pinned PC client if the target URL matches the paired PC origin;
     * otherwise returns the fast public client per contract 06 & F-40.
     */
    fun fastClientFor(url: String): OkHttpClient {
        return if (isPairedPcOrigin(url)) fastPcClient else fastPublicClient
    }

    /**
     * Evicts all pooled connections across clients so newly paired pins or endpoints take effect immediately.
     */
    fun evictConnections() {
        pcClient.connectionPool.evictAll()
        publicClient.connectionPool.evictAll()
        fastPcClient.connectionPool.evictAll()
        fastPublicClient.connectionPool.evictAll()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * Legacy callback signature for backward compatibility with FloatingButtonService.
     * Returns transcribed text on success, "" on silence, or null on error.
     */
    fun transcribe(file: File, callback: (String?) -> Unit) {
        transcribeDetailed(file) { result ->
            when (result) {
                is TranscriptionResult.Success -> callback(result.text)
                is TranscriptionResult.Empty -> callback("")
                else -> callback(null)
            }
        }
    }

    /**
     * Sends audio payload to POST /v1/audio/transcriptions with full OpenAI parameter compatibility.
     * Reason for edit: Added optional StyleOverrides parameter for per-dictation floating button adjustments (F-6).
     */
    fun transcribeDetailed(
        file: File,
        styleOverrides: StyleOverrides? = null,
        rawCallback: (TranscriptionResult) -> Unit
    ) {
        val callback: (TranscriptionResult) -> Unit = { res -> runOnMain { rawCallback(res) } }
        val url = repository.getTranscribeUrl()
        // Reason for edit: Added validation to prevent crashes when server URL is blank or unconfigured
        if (url.isBlank() || !url.startsWith("http")) {
            Log.e("ApiClient", "Invalid transcribe URL: '$url'")
            callback(TranscriptionResult.ServerError(400, "Server URL is not configured. Please open settings."))
            return
        }
        Log.d("ApiClient", "Transcribing file ${file.absolutePath} (${file.length()} bytes) to $url with overrides: $styleOverrides")

        if (!file.exists()) {
            Log.e("ApiClient", "Audio file does not exist: ${file.absolutePath}")
            callback(TranscriptionResult.ServerError(400, "Audio file does not exist"))
            return
        }

        val mediaType = if (file.name.endsWith(".m4a", ignoreCase = true)) {
            "audio/m4a".toMediaTypeOrNull()
        } else {
            "audio/wav".toMediaTypeOrNull()
        }

        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))

        // Model parameter
        val model = repository.modelName
        if (model.isNotBlank()) {
            requestBodyBuilder.addFormDataPart("model", model)
        }

        // Vocabulary hints (prompt clamped to max ~540 chars per Whisper token limits)
        val hints = repository.promptHints
        if (hints.isNotBlank()) {
            val clamped = TranscriptSanitizer.clampHints(hints)
            requestBodyBuilder.addFormDataPart("prompt", clamped)
        }

        // Self-Hosted processing mode and cleanup overrides (contracts/01 §1 & contracts/02 §5, F-8)
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
            if (styleOverrides != null) {
                if (styleOverrides.tone == "mean") {
                    // Reason for edit: Support processing=mean per contract F-8 & K-9 (Head PC only)
                    requestBodyBuilder.addFormDataPart("processing", "mean")
                } else {
                    requestBodyBuilder.addFormDataPart("processing", "clean")
                    requestBodyBuilder.addFormDataPart("cleanup_punctuation", styleOverrides.punctuation)
                    requestBodyBuilder.addFormDataPart("cleanup_rewrite", styleOverrides.rewrite)
                    val tone = if (styleOverrides.rewrite == "keep") "neutral" else styleOverrides.tone
                    requestBodyBuilder.addFormDataPart("cleanup_tone", tone)
                    requestBodyBuilder.addFormDataPart("cleanup_shorten", styleOverrides.shorten)
                    requestBodyBuilder.addFormDataPart("cleanup_emoji", repository.cleanupEmoji)
                }
            } else {
                // Reason for edit: If user explicitly set cleanup switch, send cleanup=on or cleanup=off per contract K-10
                if (repository.cleanupUserSet) {
                    requestBodyBuilder.addFormDataPart("cleanup", if (repository.cleanupEnabled) "on" else "off")
                }
                if (repository.cleanupEnabled) {
                    requestBodyBuilder.addFormDataPart("processing", "clean")
                    requestBodyBuilder.addFormDataPart("cleanup_punctuation", repository.cleanupPunctuation)
                    requestBodyBuilder.addFormDataPart("cleanup_rewrite", repository.cleanupRewrite)
                    val tone = if (repository.cleanupRewrite == "keep") "neutral" else repository.cleanupTone
                    requestBodyBuilder.addFormDataPart("cleanup_tone", tone)
                    requestBodyBuilder.addFormDataPart("cleanup_shorten", repository.cleanupShorten)
                    requestBodyBuilder.addFormDataPart("cleanup_emoji", repository.cleanupEmoji)
                } else {
                    requestBodyBuilder.addFormDataPart("processing", repository.processingMode)
                }
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyBuilder.build())

        // Optional Bearer authentication
        val token = repository.apiKey
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        clientFor(url).newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiClient", "Network failure during transcription: ${e.message}", e)
                callback(TranscriptionResult.NetworkError(mapError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val code = response.code
                    val rawBody = response.body?.string().orEmpty()
                    // Reason for edit: Gate response body behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9
                    if (BuildConfig.DEBUG) {
                        Log.d("ApiClient", "Transcription response code: $code, body: $rawBody")
                    } else {
                        Log.d("ApiClient", "Transcription response code: $code, body length: ${rawBody.length}")
                    }

                    when (code) {
                        200 -> {
                            try {
                                val json = JSONObject(rawBody)
                                val text = json.optString("text", "")
                                if (text.isBlank()) {
                                    callback(TranscriptionResult.Empty())
                                } else if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
                                    // For cloud presets: check for "prompt AI" voice edit commands (contracts/03) or cleanup pass (contracts/02)
                                    processCloudTranscription(text, styleOverrides) { processedText ->
                                        if (processedText.isBlank()) {
                                            callback(TranscriptionResult.Empty())
                                        } else {
                                            callback(TranscriptionResult.Success(processedText))
                                        }
                                    }
                                } else {
                                    callback(TranscriptionResult.Success(text))
                                }
                            } catch (e: Exception) {
                                Log.e("ApiClient", "Failed to parse 200 JSON response", e)
                                callback(TranscriptionResult.ServerError(200, "Invalid JSON from server"))
                            }
                        }
                        401 -> {
                            callback(TranscriptionResult.AuthError("API key rejected by server"))
                        }
                        503 -> {
                            // Reason for edit: Differentiate between soft pause (server_paused) and model startup per contract K-4
                            var errCode = ""
                            var errMsg = "Service unavailable"
                            try {
                                val errJson = JSONObject(rawBody).optJSONObject("error")
                                if (errJson != null) {
                                    errCode = errJson.optString("code", "")
                                    errMsg = errJson.optString("message", errMsg)
                                }
                            } catch (_: Exception) {}

                            if (errCode == "server_paused") {
                                callback(TranscriptionResult.ServerPaused(errMsg))
                            } else {
                                val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 30
                                callback(TranscriptionResult.ModelUnavailable(retryAfter))
                            }
                        }
                        else -> {
                            var message = response.message
                            try {
                                val errJson = JSONObject(rawBody).optJSONObject("error")
                                if (errJson != null) {
                                    message = errJson.optString("message", message)
                                }
                            } catch (_: Exception) {}
                            callback(TranscriptionResult.ServerError(code, message))
                        }
                    }
                }
            }
        })
    }

    /**
     * Checks server health via GET /health (unauthenticated, cheap check).
     * Reason for edit: Treat 'status: paused' as reachable per contract K-4.
     */
    fun checkHealth(rawCallback: (HealthResult) -> Unit) {
        val callback: (HealthResult) -> Unit = { res -> runOnMain { rawCallback(res) } }
        val healthUrl = repository.getHealthUrl()
        if (healthUrl.isBlank() || !healthUrl.startsWith("http")) {
            callback(HealthResult.ServiceError("Server URL is not configured"))
            return
        }
        val request = Request.Builder()
            .url(healthUrl)
            .get()
            .build()

        fastClientFor(healthUrl).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("ApiClient", "Health check failed at $healthUrl: ${e.message}")
                callback(HealthResult.Unreachable(mapError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback(HealthResult.ServiceError("Health check returned HTTP ${response.code}"))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    try {
                        val json = JSONObject(body)
                        val status = json.optString("status", "")
                        val model = if (json.has("model") && !json.isNull("model")) json.getString("model") else null
                        val authRequired = json.optBoolean("auth_required", false)
                        val cleanupAlways = if (json.has("cleanup_always")) json.optBoolean("cleanup_always") else null

                        if (status.equals("ok", ignoreCase = true)) {
                            replayPendingMutations()
                            callback(HealthResult.Ok(model, authRequired, cleanupAlways))
                        } else if (status.equals("paused", ignoreCase = true)) {
                            val pausedReason = if (json.has("paused_reason") && !json.isNull("paused_reason")) {
                                json.getString("paused_reason")
                            } else null
                            replayPendingMutations()
                            callback(HealthResult.Paused(pausedReason, model, authRequired, cleanupAlways))
                        } else {
                            callback(HealthResult.ServiceError("Server status: $status"))
                        }
                    } catch (e: Exception) {
                        callback(HealthResult.ServiceError("Invalid JSON response from /health"))
                    }
                }
            }
        })
    }

    /**
     * Validates API credentials and connection against GET /v1/models.
     */
    fun testConnection(rawCallback: (Boolean, String) -> Unit) {
        val callback: (Boolean, String) -> Unit = { ok, msg -> runOnMain { rawCallback(ok, msg) } }
        val modelsUrl = repository.getModelsUrl()
        if (modelsUrl.isBlank() || !modelsUrl.startsWith("http")) {
            callback(false, "Server URL is not configured. Please enter your server endpoint or scan pairing code.")
            return
        }
        val requestBuilder = Request.Builder().url(modelsUrl).get()

        val token = repository.apiKey
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        fastClientFor(modelsUrl).newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val mapped = mapError(e)
                callback(false, "Connection failed: ${mapped.localizedMessage ?: e.localizedMessage}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        callback(true, "Connected successfully!")
                    } else if (response.code == 401) {
                        callback(false, "API key rejected (401 Unauthorized)")
                    } else {
                        callback(false, "Server returned HTTP ${response.code}: ${response.message}")
                    }
                }
            }
        })
    }

    /**
     * Retrieves shared vocabulary from GET /v1/vocabulary (contracts/01-transcription-api.md §8).
     * Reason for edit: Added support for shared vocabulary lookup per K-2.
     */
    fun getVocabulary(rawCallback: (VocabularyResult) -> Unit) {
        val callback: (VocabularyResult) -> Unit = { res -> runOnMain { rawCallback(res) } }
        val url = repository.getVocabularyUrl()
        if (url.isBlank() || !url.startsWith("http")) {
            callback(VocabularyResult.Error(400, "Server URL is not configured"))
            return
        }
        val requestBuilder = Request.Builder().url(url).get()

        val token = repository.apiKey
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        fastClientFor(url).newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(VocabularyResult.NetworkError(mapError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(body)
                            val vocabArray = json.optJSONArray("vocabulary") ?: JSONArray()
                            val vocabList = (0 until vocabArray.length()).map { vocabArray.getString(it) }

                            val nameArray = json.optJSONArray("from_name_corrections") ?: JSONArray()
                            val nameList = (0 until nameArray.length()).map { nameArray.getString(it) }

                            val nameCorrectionsObj = json.optJSONObject("name_corrections")
                            val nameCorrectionsMap = mutableMapOf<String, String>()
                            if (nameCorrectionsObj != null) {
                                for (k in nameCorrectionsObj.keys()) {
                                    nameCorrectionsMap[k] = nameCorrectionsObj.getString(k)
                                }
                            }

                            val chars = json.optInt("chars", 0)
                            val maxChars = json.optInt("max_chars", 540)

                            // Reason for edit: Cache shared vocabulary and server name corrections in SettingsRepository for on-device offline use per contract 05 §2.4
                            repository.cachedServerVocabulary = vocabList.toSet()
                            if (nameCorrectionsObj != null) {
                                repository.cachedServerNameCorrectionsJson = nameCorrectionsObj.toString()
                            }

                            callback(VocabularyResult.Success(VocabularyData(vocabList, nameList, nameCorrectionsMap, chars, maxChars)))
                        } catch (e: Exception) {
                            callback(VocabularyResult.Error(response.code, "Failed to parse vocabulary response: ${e.message}"))
                        }
                    } else {
                        callback(VocabularyResult.Error(response.code, "Server returned HTTP ${response.code}: ${response.message}"))
                    }
                }
            }
        })
    }

    /**
     * Modifies shared vocabulary via POST /v1/vocabulary (contracts/01-transcription-api.md §8).
     * Takes {"add": [...], "remove": [...]}. Removes run first.
     * Reason for edit: Added full add & remove mutation support per contract 01 §8 and F-43.
     */
    fun modifyVocabulary(
        add: List<String> = emptyList(),
        remove: List<String> = emptyList(),
        rawCallback: (VocabularyResult) -> Unit
    ) {
        val callback: (VocabularyResult) -> Unit = { res -> runOnMain { rawCallback(res) } }
        if (add.isEmpty() && remove.isEmpty()) return

        val url = repository.getVocabularyUrl()
        if (url.isBlank() || !url.startsWith("http")) {
            callback(VocabularyResult.Error(400, "Server URL is not configured"))
            return
        }
        val jsonPayload = JSONObject().apply {
            if (add.isNotEmpty()) put("add", JSONArray(add.take(50)))
            if (remove.isNotEmpty()) put("remove", JSONArray(remove.take(50)))
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        val token = repository.apiKey
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        fastClientFor(url).newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(VocabularyResult.NetworkError(mapError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(body)
                            val vocabArray = json.optJSONArray("vocabulary") ?: JSONArray()
                            val vocabList = (0 until vocabArray.length()).map { vocabArray.getString(it) }

                            val nameArray = json.optJSONArray("from_name_corrections") ?: JSONArray()
                            val nameList = (0 until nameArray.length()).map { nameArray.getString(it) }

                            val nameCorrectionsObj = json.optJSONObject("name_corrections")
                            val nameCorrectionsMap = mutableMapOf<String, String>()
                            if (nameCorrectionsObj != null) {
                                for (k in nameCorrectionsObj.keys()) {
                                    nameCorrectionsMap[k] = nameCorrectionsObj.getString(k)
                                }
                                repository.cachedServerNameCorrectionsJson = nameCorrectionsObj.toString()
                            }

                            val addedArray = json.optJSONArray("added") ?: JSONArray()
                            val addedList = (0 until addedArray.length()).map { addedArray.getString(it) }

                            val removedArray = json.optJSONArray("removed") ?: JSONArray()
                            val removedList = (0 until removedArray.length()).map { removedArray.getString(it) }

                            val chars = json.optInt("chars", 0)
                            val maxChars = json.optInt("max_chars", 540)

                            repository.cachedServerVocabulary = vocabList.toSet()

                            callback(VocabularyResult.Success(VocabularyData(vocabList, nameList, nameCorrectionsMap, chars, maxChars, addedList, removedList)))
                        } catch (e: Exception) {
                            callback(VocabularyResult.Error(response.code, "Failed to parse vocabulary response: ${e.message}"))
                        }
                    } else {
                        val errMsg = extractErrorMessage(body)
                        callback(VocabularyResult.Error(response.code, errMsg ?: "Server returned HTTP ${response.code}: ${response.message}"))
                    }
                }
            }
        })
    }

    fun addVocabulary(words: List<String>, rawCallback: (VocabularyResult) -> Unit) {
        modifyVocabulary(add = words, rawCallback = rawCallback)
    }

    /**
     * Modifies name corrections via POST /v1/name_corrections (contracts/01-transcription-api.md §8).
     * Takes {"set": {"Corey": "Cory"}, "remove": ["Emma"]}.
     * Reason for edit: Added name corrections mutation endpoint per contract 01 §8 and F-43.
     */
    fun modifyNameCorrections(
        set: Map<String, String> = emptyMap(),
        remove: List<String> = emptyList(),
        rawCallback: (NameCorrectionsResult) -> Unit
    ) {
        val callback: (NameCorrectionsResult) -> Unit = { res -> runOnMain { rawCallback(res) } }
        if (set.isEmpty() && remove.isEmpty()) return

        val url = repository.getNameCorrectionsUrl()
        if (url.isBlank() || !url.startsWith("http")) {
            callback(NameCorrectionsResult.Error(400, "Server URL is not configured"))
            return
        }

        val jsonPayload = JSONObject().apply {
            if (set.isNotEmpty()) {
                val setObj = JSONObject()
                for ((k, v) in set) setObj.put(k, v)
                put("set", setObj)
            }
            if (remove.isNotEmpty()) {
                put("remove", JSONArray(remove.take(50)))
            }
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        val token = repository.apiKey
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        fastClientFor(url).newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(NameCorrectionsResult.NetworkError(mapError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (response.code == 404) {
                        callback(NameCorrectionsResult.Unsupported("Update the PC app to edit these here"))
                        return
                    }
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(body)
                            val nameCorrectionsObj = json.optJSONObject("name_corrections")
                            val nameCorrectionsMap = mutableMapOf<String, String>()
                            if (nameCorrectionsObj != null) {
                                for (k in nameCorrectionsObj.keys()) {
                                    nameCorrectionsMap[k] = nameCorrectionsObj.getString(k)
                                }
                                repository.cachedServerNameCorrectionsJson = nameCorrectionsObj.toString()
                            }

                            val vocabArray = json.optJSONArray("vocabulary")
                            if (vocabArray != null) {
                                val vocabList = (0 until vocabArray.length()).map { vocabArray.getString(it) }
                                repository.cachedServerVocabulary = vocabList.toSet()
                            }

                            val changedObj = json.optJSONObject("changed")
                            val changedMap = mutableMapOf<String, String>()
                            if (changedObj != null) {
                                for (k in changedObj.keys()) {
                                    changedMap[k] = changedObj.getString(k)
                                }
                            }

                            val removedArray = json.optJSONArray("removed") ?: JSONArray()
                            val removedList = (0 until removedArray.length()).map { removedArray.getString(it) }

                            callback(NameCorrectionsResult.Success(nameCorrectionsMap, changedMap, removedList))
                        } catch (e: Exception) {
                            callback(NameCorrectionsResult.Error(response.code, "Failed to parse name corrections response: ${e.message}"))
                        }
                    } else {
                        val errMsg = extractErrorMessage(body)
                        callback(NameCorrectionsResult.Error(response.code, errMsg ?: "Server returned HTTP ${response.code}: ${response.message}"))
                    }
                }
            }
        })
    }

    /**
     * Replays queued offline word and name correction mutations in order.
     * Guarded by AtomicBoolean so concurrent calls cannot interleave or duplicate mutations.
     * Re-reads the pending queue from SettingsRepository at each step.
     * Reason for edit: Added replayPendingMutations per contract 01 §8, F-43, and F-44 review.
     */
    fun replayPendingMutations(onComplete: ((Boolean) -> Unit)? = null) {
        if (!isReplaying.compareAndSet(false, true)) {
            onComplete?.invoke(false)
            return
        }
        replayNextMutation(onComplete)
    }

    private fun replayNextMutation(onComplete: ((Boolean) -> Unit)?) {
        val mutations = repository.getPendingMutations()
        if (mutations.isEmpty()) {
            isReplaying.set(false)
            onComplete?.invoke(true)
            return
        }
        val item = mutations.first()
        when (item.type) {
            "vocab" -> {
                modifyVocabulary(add = item.addWords, remove = item.removeWords) { result ->
                    when (result) {
                        is VocabularyResult.Success -> {
                            repository.removePendingMutation(item.id)
                            replayNextMutation(onComplete)
                        }
                        is VocabularyResult.Error -> {
                            if (result.code == 400) {
                                repository.removePendingMutation(item.id)
                                runOnMain {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                                replayNextMutation(onComplete)
                            } else {
                                isReplaying.set(false)
                                onComplete?.invoke(false)
                            }
                        }
                        is VocabularyResult.NetworkError -> {
                            isReplaying.set(false)
                            onComplete?.invoke(false)
                        }
                    }
                }
            }
            "name" -> {
                modifyNameCorrections(set = item.setNameCorrections, remove = item.removeNameCorrections) { result ->
                    when (result) {
                        is NameCorrectionsResult.Success -> {
                            repository.removePendingMutation(item.id)
                            replayNextMutation(onComplete)
                        }
                        is NameCorrectionsResult.Unsupported -> {
                            // Older server (404): drop item, notify user, and restore cache from PC
                            repository.removePendingMutation(item.id)
                            runOnMain {
                                Toast.makeText(
                                    context,
                                    "Your PC app is too old to change name corrections from the phone. Update it, then make this change again.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            getVocabulary { _ -> }
                            replayNextMutation(onComplete)
                        }
                        is NameCorrectionsResult.Error -> {
                            if (result.code == 400) {
                                repository.removePendingMutation(item.id)
                                runOnMain {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                                replayNextMutation(onComplete)
                            } else {
                                isReplaying.set(false)
                                onComplete?.invoke(false)
                            }
                        }
                        is NameCorrectionsResult.NetworkError -> {
                            isReplaying.set(false)
                            onComplete?.invoke(false)
                        }
                    }
                }
            }
            else -> {
                repository.removePendingMutation(item.id)
                replayNextMutation(onComplete)
            }
        }
    }

    /**
     * Routes cloud transcriptions to either "prompt AI" voice edit commands (contracts/03) or cleanup pass (contracts/02).
     * Reason for edit: Added support for contracts/03-edit-commands.md (K-5) and per-dictation StyleOverrides (F-6).
     */
    private fun processCloudTranscription(
        text: String,
        styleOverrides: StyleOverrides? = null,
        callback: (String) -> Unit
    ) {
        try {
            val editPromptJson = context.assets.open("03-edit-prompt.json").bufferedReader().use { it.readText() }
            val editProcessor = EditCommandProcessor(editPromptJson)
            val split = editProcessor.split(text)

            if (split.isCommand) {
                // contracts/03 §4: When an edit command fired, cleanup does NOT run on that dictation
                executeEditCommandWithCloudLlm(split, editProcessor, callback)
                return
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Error checking edit command triggers", e)
        }

        // Not a voice command: run cleanup if enabled or if overridden per contracts/02 & F-6
        if (styleOverrides != null || repository.cleanupEnabled) {
            cleanTranscriptWithCloudLlm(text, styleOverrides, callback)
        } else {
            callback(text)
        }
    }

    /**
     * Executes client-side LLM cleanup for cloud ASR presets per contracts/02-cleanup.md (Version 2).
     * Reason for edit: Added support for per-dictation StyleOverrides (F-6).
     */
    private fun cleanTranscriptWithCloudLlm(
        text: String,
        styleOverrides: StyleOverrides? = null,
        callback: (String) -> Unit
    ) {
        val chatUrl = repository.cleanupChatUrl
        if (chatUrl.isBlank()) {
            callback(text)
            return
        }

        try {
            val assetPrompt = context.assets.open("02-cleanup-prompt.json").bufferedReader().use { it.readText() }
            val cleaner = TranscriptCleaner(assetPrompt)
            val punc = styleOverrides?.punctuation ?: repository.cleanupPunctuation
            val rew = styleOverrides?.rewrite ?: repository.cleanupRewrite
            val tone = if (rew == "keep") "neutral" else (styleOverrides?.tone ?: repository.cleanupTone)
            val short = styleOverrides?.shorten ?: repository.cleanupShorten
            val options = CleanupOptions(
                punctuation = punc,
                rewrite = rew,
                tone = tone,
                shorten = short,
                emoji = repository.cleanupEmoji
            )
            val localVocab = repository.promptHints.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val systemPrompt = cleaner.buildSystemPrompt(options, localVocab)
            val userMsg = cleaner.buildUserMessage(text)

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMsg)
                })
            }

            val payload = JSONObject().apply {
                put("model", repository.cleanupChatModel)
                put("temperature", 0.2)
                put("stream", false)
                put("messages", messagesArray)
            }

            val requestBuilder = Request.Builder()
                .url(chatUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))

            val key = repository.cleanupChatKey
            if (key.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $key")
            }

            fastClientFor(chatUrl).newCall(requestBuilder.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("ApiClient", "Cloud cleanup LLM call failed; falling back to raw transcript", e)
                    callback(text)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.w("ApiClient", "Cloud cleanup HTTP ${response.code}; falling back to raw transcript")
                            callback(text)
                            return
                        }
                        try {
                            val resJson = JSONObject(response.body?.string().orEmpty())
                            val choices = resJson.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val replyContent = choices.getJSONObject(0).optJSONObject("message")?.optString("content", "").orEmpty()
                                val tidied = cleaner.tidyReply(replyContent, text)
                                callback(tidied)
                            } else {
                                callback(text)
                            }
                        } catch (e: Exception) {
                            Log.w("ApiClient", "Failed to parse cloud cleanup reply", e)
                            callback(text)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to initialize TranscriptCleaner from assets", e)
            callback(text)
        }
    }

    /**
     * Executes "prompt AI" voice commands using the configured cloud LLM per contracts/03-edit-commands.md.
     */
    private fun executeEditCommandWithCloudLlm(
        split: EditCommandSplit,
        processor: EditCommandProcessor,
        callback: (String) -> Unit
    ) {
        if (split.content.isEmpty() && split.instruction.isEmpty()) {
            // Nothing to send: paste empty string (contracts/03-edit-commands.md §2)
            callback("")
            return
        }

        val chatUrl = repository.cleanupChatUrl
        if (chatUrl.isBlank()) {
            callback(split.content.ifEmpty { split.original })
            return
        }

        try {
            val userMsg = processor.buildUserMessage(split)
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", processor.systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMsg)
                })
            }

            val payload = JSONObject().apply {
                put("model", repository.cleanupChatModel)
                put("temperature", 0.2)
                put("stream", false)
                put("messages", messagesArray)
                put("chat_template_kwargs", JSONObject().apply {
                    put("enable_thinking", false)
                })
            }

            val requestBuilder = Request.Builder()
                .url(chatUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))

            val key = repository.cleanupChatKey
            if (key.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $key")
            }

            fastClientFor(chatUrl).newCall(requestBuilder.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("ApiClient", "Cloud edit command LLM call failed; falling back to content", e)
                    callback(split.content.ifEmpty { split.original })
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.w("ApiClient", "Cloud edit command returned HTTP ${response.code}; falling back")
                            callback(split.content.ifEmpty { split.original })
                            return
                        }
                        val body = response.body?.string().orEmpty()
                        try {
                            val resJson = JSONObject(body)
                            val choice = resJson.getJSONArray("choices").getJSONObject(0)
                            val rawContent = choice.getJSONObject("message").optString("content", "")
                            val tidied = processor.tidyReply(rawContent, split)
                            callback(tidied)
                        } catch (e: Exception) {
                            Log.w("ApiClient", "Failed to parse edit LLM response; falling back", e)
                            callback(split.content.ifEmpty { split.original })
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to execute edit command", e)
            callback(split.content.ifEmpty { split.original })
        }
    }

    companion object {
        const val CERT_MISMATCH_MESSAGE =
            "Your PC's security certificate changed. If you reinstalled Whisper Unchained Server, scan its pairing code again."

        /**
         * Extracts readable error messages from OpenAI-style JSON responses:
         * {"error": {"message": "..."}}, falling back to top-level "message" or "detail".
         * Conforms to contract 01 §8 and F-44.
         */
        fun extractErrorMessage(body: String): String? {
            if (body.isBlank()) return null
            return try {
                val json = JSONObject(body)
                val errorObj = json.optJSONObject("error")
                if (errorObj != null) {
                    val msg = errorObj.optString("message").trim()
                    if (msg.isNotEmpty()) return msg
                }
                val msg = json.optString("message").trim()
                if (msg.isNotEmpty()) return msg
                val detail = json.optString("detail").trim()
                if (detail.isNotEmpty()) return detail
                null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Returns true if the given error was caused by a pinned certificate mismatch or TLS handshake failure.
         */
        fun isCertMismatch(e: Throwable?): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                val msg = cur.message.orEmpty()
                if (msg.contains("security certificate changed", ignoreCase = true) ||
                    msg.contains("Pin mismatch", ignoreCase = true)
                ) {
                    return true
                }
                if (cur is java.security.cert.CertificateException ||
                    cur is javax.net.ssl.SSLHandshakeException ||
                    cur is javax.net.ssl.SSLException
                ) {
                    if (msg.contains("certificate", ignoreCase = true) ||
                        msg.contains("CertPathValidatorException", ignoreCase = true)
                    ) {
                        return true
                    }
                }
                cur = cur.cause
            }
            return false
        }

        /**
         * Wraps TLS/pin-mismatch exceptions with user-friendly actionable text per contract 06 §3.4.
         */
        fun mapError(e: IOException): IOException {
            if (isCertMismatch(e)) {
                return IOException(CERT_MISMATCH_MESSAGE, e)
            }
            return e
        }
    }
}
