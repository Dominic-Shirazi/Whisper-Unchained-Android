// This file provides the HTTP API client for Whisper Unchained.
// It is called by FloatingButtonService (for audio transcription) and MainActivity (for connection & health checks).
// Inputs: audio File (WAV/M4A), optional prompt hints, API key, model name from SettingsRepository.
// Outputs: TranscriptionResult (transcribed text or typed error), HealthResult, and model list.
// It speaks the OpenAI-compatible standard POST /v1/audio/transcriptions, GET /health, and GET /v1/models.

package com.example.whisperremote

// Reason for edit: Updated per contract K-4 to support soft pause (status 'paused' in /health, 503 'server_paused' in transcriptions) and product rename to Whisper Unchained.

import android.content.Context
import android.util.Log
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
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
import java.util.concurrent.TimeUnit

sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class Empty(val message: String = "No speech detected") : TranscriptionResult()
    data class ServerPaused(val message: String, val reason: String? = null) : TranscriptionResult()
    data class ModelUnavailable(val retryAfterSeconds: Int) : TranscriptionResult()
    data class AuthError(val message: String) : TranscriptionResult()
    data class ServerError(val code: Int, val message: String) : TranscriptionResult()
    data class NetworkError(val error: Throwable) : TranscriptionResult()
}

sealed class HealthResult {
    data class Ok(val model: String?, val authRequired: Boolean) : HealthResult()
    data class Paused(val reason: String?, val model: String?, val authRequired: Boolean) : HealthResult()
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
    val added: List<String> = emptyList()
)

sealed class VocabularyResult {
    data class Success(val data: VocabularyData) : VocabularyResult()
    data class Error(val code: Int, val message: String) : VocabularyResult()
    data class NetworkError(val error: Throwable) : VocabularyResult()
}

class ApiClient(private val context: Context) {

    private val repository = SettingsRepository(context)

    // Transcription client with standard 60-second timeouts for GPU/LLM processing
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Fast client for health checks (3-5s timeout per contract §2)
    private val fastCheckClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

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
     */
    fun transcribeDetailed(file: File, callback: (TranscriptionResult) -> Unit) {
        val url = repository.getTranscribeUrl()
        // Reason for edit: Added validation to prevent crashes when server URL is blank or unconfigured
        if (url.isBlank() || !url.startsWith("http")) {
            Log.e("ApiClient", "Invalid transcribe URL: '$url'")
            callback(TranscriptionResult.ServerError(400, "Server URL is not configured. Please open settings."))
            return
        }
        Log.d("ApiClient", "Transcribing file ${file.absolutePath} (${file.length()} bytes) to $url")

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

        // Self-Hosted processing mode and cleanup overrides (contracts/01 §1 & contracts/02 §5)
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
            if (repository.cleanupEnabled) {
                requestBodyBuilder.addFormDataPart("processing", "clean")
                requestBodyBuilder.addFormDataPart("cleanup_punctuation", repository.cleanupPunctuation)
                requestBodyBuilder.addFormDataPart("cleanup_rewrite", repository.cleanupRewrite)
                requestBodyBuilder.addFormDataPart("cleanup_tone", repository.cleanupTone)
                requestBodyBuilder.addFormDataPart("cleanup_shorten", repository.cleanupShorten)
                requestBodyBuilder.addFormDataPart("cleanup_emoji", repository.cleanupEmoji)
            } else {
                requestBodyBuilder.addFormDataPart("processing", repository.processingMode)
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

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiClient", "Network failure during transcription: ${e.message}", e)
                callback(TranscriptionResult.NetworkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val code = response.code
                    val rawBody = response.body?.string().orEmpty()
                    Log.d("ApiClient", "Transcription response code: $code, body: $rawBody")

                    when (code) {
                        200 -> {
                            try {
                                val json = JSONObject(rawBody)
                                val text = json.optString("text", "")
                                if (text.isBlank()) {
                                    callback(TranscriptionResult.Empty())
                                } else if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
                                    // For cloud presets: check for "prompt AI" voice edit commands (contracts/03) or cleanup pass (contracts/02)
                                    processCloudTranscription(text) { processedText ->
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
    fun checkHealth(callback: (HealthResult) -> Unit) {
        val healthUrl = repository.getHealthUrl()
        if (healthUrl.isBlank() || !healthUrl.startsWith("http")) {
            callback(HealthResult.ServiceError("Server URL is not configured"))
            return
        }
        val request = Request.Builder()
            .url(healthUrl)
            .get()
            .build()

        fastCheckClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("ApiClient", "Health check failed at $healthUrl: ${e.message}")
                callback(HealthResult.Unreachable(e))
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

                        if (status.equals("ok", ignoreCase = true)) {
                            callback(HealthResult.Ok(model, authRequired))
                        } else if (status.equals("paused", ignoreCase = true)) {
                            val pausedReason = if (json.has("paused_reason") && !json.isNull("paused_reason")) {
                                json.getString("paused_reason")
                            } else null
                            callback(HealthResult.Paused(pausedReason, model, authRequired))
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
    fun testConnection(callback: (Boolean, String) -> Unit) {
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

        fastCheckClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, "Connection failed: ${e.localizedMessage}")
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
    fun getVocabulary(callback: (VocabularyResult) -> Unit) {
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

        fastCheckClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(VocabularyResult.NetworkError(e))
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
     * Adds words to shared vocabulary via POST /v1/vocabulary (contracts/01-transcription-api.md §8).
     */
    fun addVocabulary(words: List<String>, callback: (VocabularyResult) -> Unit) {
        if (words.isEmpty()) return

        val url = repository.getVocabularyUrl()
        if (url.isBlank() || !url.startsWith("http")) {
            callback(VocabularyResult.Error(400, "Server URL is not configured"))
            return
        }
        val jsonPayload = JSONObject().apply {
            put("add", JSONArray(words.take(50)))
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        val token = repository.apiKey
        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        fastCheckClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(VocabularyResult.NetworkError(e))
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

                            val addedArray = json.optJSONArray("added") ?: JSONArray()
                            val addedList = (0 until addedArray.length()).map { addedArray.getString(it) }

                            val chars = json.optInt("chars", 0)
                            val maxChars = json.optInt("max_chars", 540)

                            callback(VocabularyResult.Success(VocabularyData(vocabList, nameList, nameCorrectionsMap, chars, maxChars, addedList)))
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
     * Routes cloud transcriptions to either "prompt AI" voice edit commands (contracts/03) or cleanup pass (contracts/02).
     * Reason for edit: Added support for contracts/03-edit-commands.md (K-5).
     */
    private fun processCloudTranscription(text: String, callback: (String) -> Unit) {
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

        // Not a voice command: run cleanup if enabled per contracts/02
        if (repository.cleanupEnabled) {
            cleanTranscriptWithCloudLlm(text, callback)
        } else {
            callback(text)
        }
    }

    /**
     * Executes client-side LLM cleanup for cloud ASR presets per contracts/02-cleanup.md (Version 2).
     * Reason for edit: Added cloud LLM cleanup pipeline assembling prompts and tidying output with tone support.
     */
    private fun cleanTranscriptWithCloudLlm(text: String, callback: (String) -> Unit) {
        val chatUrl = repository.cleanupChatUrl
        if (chatUrl.isBlank()) {
            callback(text)
            return
        }

        try {
            val assetPrompt = context.assets.open("02-cleanup-prompt.json").bufferedReader().use { it.readText() }
            val cleaner = TranscriptCleaner(assetPrompt)
            val options = CleanupOptions(
                punctuation = repository.cleanupPunctuation,
                rewrite = repository.cleanupRewrite,
                tone = repository.cleanupTone,
                shorten = repository.cleanupShorten,
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

            fastCheckClient.newCall(requestBuilder.build()).enqueue(object : Callback {
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

            fastCheckClient.newCall(requestBuilder.build()).enqueue(object : Callback {
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
}
