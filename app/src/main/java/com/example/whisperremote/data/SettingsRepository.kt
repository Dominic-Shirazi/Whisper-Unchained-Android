// This file manages persistent configuration for Whisper Unchained.
// It is called by MainActivity, FloatingButtonService, and ApiClient.
// Inputs: User inputs from Settings UI or QR code scan, with sensitive credentials (API keys) protected by hardware-backed KeyStore AES-256-GCM.
// Outputs: Provider settings (base URL, API key, model, prompt hints), behavioral flags (auto-trim, boot start, Tailscale auto-restart), and injection mode.
// It resolves endpoints according to the OpenAI standard (/v1/audio/transcriptions, /health, /v1/models) and migrates legacy URLs cleanly.
// Reason for edit: Add on-device model and download preferences per contract 05 §2.3 & §2.6.
// Reason for edit: Accurate provider info (corrected Together pricing, realistic speed claims, verified privacy summaries, pricing URLs) per F-27.

package com.example.whisperremote.data

// Reason for edit: Added isServerVerified and isSetupDismissedManually tracking for setup checklist per contract F-16.
// Reason for edit: Added QrPayload validation (scheme http/https, non-empty host, /v1 suffix) and host confirmation support per contract F-12.
// Reason for edit: Gate pref repair logs behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9.
// Reason for edit: Encrypt only real secrets (api_key, cleanup_chat_key) and auto-repair/decrypt non-secret preferences (cleanup_punctuation, options, URLs) per Q-3.

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.whisperremote.BuildConfig
import com.example.whisperremote.util.KeyStoreCryptoHelper
import org.json.JSONObject

data class QrPayload(
    val baseUrl: String,
    val apiKey: String?,
    val model: String?,
    val name: String?,
    val hostDisplay: String
)

enum class InjectionMode {
    ACCESSIBILITY,
    CLIPBOARD
}

// Reason for edit: Add TranscriptionTarget enum for On this phone / My PC / Cloud service per contract 05 §2.3
enum class TranscriptionTarget(val id: String, val displayName: String) {
    ON_DEVICE("phone", "On this phone"),
    PC("pc", "My PC"),
    CLOUD("cloud", "Cloud service");

    companion object {
        fun fromId(id: String): TranscriptionTarget {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PC
        }
    }
}

enum class ProviderPreset(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val tagLine: String,
    val privacySummary: String,
    val pricingSummary: String,
    val speedSummary: String,
    val pricingUrl: String? = null
) {
    SELF_HOSTED(
        displayName = "Self-Hosted",
        defaultBaseUrl = "",
        defaultModel = "large-v3-turbo",
        tagLine = "Default · Most Private",
        privacySummary = "Audio goes only to your own PC. Your PC keeps a transcript log you control. Nothing goes to a cloud service unless you set one up.",
        pricingSummary = "Free ($0.00). Runs entirely on your own computer hardware.",
        speedSummary = "Instant local GPU processing.",
        pricingUrl = null
    ),
    GROQ(
        displayName = "Groq Cloud",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "whisper-large-v3-turbo",
        tagLine = "Recommended Cloud · Zero Training",
        privacySummary = "Groq says API audio is not used to train models; see Groq's privacy policy.",
        pricingSummary = "~$0.04 / hour of audio (as of Nov 2026). Generous free tier available with your own API key.",
        speedSummary = "Very fast (usually well under a second for short clips).",
        pricingUrl = "https://groq.com/pricing/"
    ),
    TOGETHER(
        displayName = "Together AI",
        defaultBaseUrl = "https://api.together.xyz/v1",
        defaultModel = "whisper-large-v3",
        tagLine = "Zero Training · Dedicated Whisper",
        privacySummary = "Zero model training. Commercial API terms guarantee customer inputs and audio are never used to train models.",
        pricingSummary = "~$0.0015 / minute (~$0.09 / hour as of Nov 2026). Billed directly by Together AI with your own API key.",
        speedSummary = "High-speed processing on dedicated GPU clusters.",
        pricingUrl = "https://www.together.ai/pricing"
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "whisper-1",
        tagLine = "Official Whisper",
        privacySummary = "No training on commercial API data. Audio is retained on OpenAI servers for 30 days for abuse monitoring before automatic deletion.",
        pricingSummary = "~$0.006 / minute (~$0.36 / hour as of Nov 2026). Billed directly by OpenAI with your own API key.",
        speedSummary = "Moderate (~800ms - 1.5s).",
        pricingUrl = "https://openai.com/api/pricing/"
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/whisper-large-v3",
        tagLine = "Multi-Provider Router",
        privacySummary = "Proxy aggregator. Your request passes through OpenRouter before forwarding to the underlying model provider, subject to both privacy policies.",
        pricingSummary = "Pass-through upstream rates with flexible credit system.",
        speedSummary = "Slightly slower due to extra proxy routing hop.",
        pricingUrl = "https://openrouter.ai/models/openai/whisper-large-v3"
    ),
    CUSTOM(
        displayName = "Custom Endpoint",
        defaultBaseUrl = "",
        defaultModel = "",
        tagLine = "Your Own Server / Proxy",
        privacySummary = "Controlled entirely by the server or reverse proxy endpoint you specify (e.g. Speaches, Ollama, RunPod, vLLM).",
        pricingSummary = "Determined by your hosting provider or self-hosted server.",
        speedSummary = "Depends on your server hardware and network connection.",
        pricingUrl = null
    );

    companion object {
        fun fromLegacy(name: String?): ProviderPreset {
            return when (name) {
                "HEAD_PC" -> SELF_HOSTED
                "OLLAMA" -> CUSTOM
                "OPENROUTER_OPENAI" -> OPENAI
                else -> values().find { it.name == name } ?: SELF_HOSTED
            }
        }
    }
}

// Reason for edit: Added chat presets for cloud dictation cleanup per Q-2
enum class ChatProviderPreset(val displayName: String, val defaultUrl: String, val defaultModel: String) {
    GROQ("Groq (llama-3.3-70b)", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
    TOGETHER("Together AI", "https://api.together.xyz/v1/chat/completions", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    OPENAI("OpenAI (gpt-4o-mini)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "meta-llama/llama-3.3-70b-instruct"),
    OLLAMA("Ollama / LocalAI (Tailscale/LAN)", "http://100.x.y.z:11434/v1/chat/completions", "llama3.2"),
    CUSTOM("Custom Endpoint", "", "");

    companion object {
        fun fromLegacy(name: String?): ChatProviderPreset {
            return when (name) {
                "OPENROUTER_OPENAI" -> OPENAI
                else -> values().find { it.name == name } ?: GROQ
            }
        }
    }
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isHardwareEncrypted: Boolean
        get() = KeyStoreCryptoHelper.isHardwareAvailable()

    init {
        migrateFromEncryptedSharedPreferences(context)
        migrateFromLegacyPlaintext(context)
        repairCiphertextInPlaintextPrefs()
    }

    private fun putSecretString(key: String, value: String) {
        val cipher = if (value.isNotBlank()) KeyStoreCryptoHelper.encrypt(value) else ""
        prefs.edit().putString(key, cipher).apply()
    }

    private fun getSecretString(key: String, defaultValue: String = ""): String {
        val raw = prefs.getString(key, null) ?: return defaultValue
        if (raw.isBlank()) return defaultValue
        return KeyStoreCryptoHelper.decrypt(raw)
    }

    private fun putPlainString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun getPlainString(key: String, defaultValue: String = ""): String {
        val raw = prefs.getString(key, null) ?: return defaultValue
        return if (raw.startsWith("enc:v1:")) {
            val decrypted = KeyStoreCryptoHelper.decrypt(raw)
            prefs.edit().putString(key, decrypted).apply()
            decrypted
        } else {
            raw
        }
    }

    /**
     * Repairs any non-secret preferences that were accidentally stored as "enc:v1:..."
     * during the initial migration pass. Idempotent and runs on startup.
     */
    private fun repairCiphertextInPlaintextPrefs() {
        val all = prefs.all
        val editor = prefs.edit()
        var changed = false
        for ((key, value) in all) {
            if (key !in SECRET_KEYS && value is String && value.startsWith("enc:v1:")) {
                val decrypted = KeyStoreCryptoHelper.decrypt(value)
                editor.putString(key, decrypted)
                changed = true
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Repaired accidentally encrypted non-secret key '$key' -> '$decrypted'")
                }
            }
        }
        if (changed) {
            editor.apply()
        }
    }

    private fun migrateFromEncryptedSharedPreferences(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encPrefs = EncryptedSharedPreferences.create(
                context,
                "WhisperRemoteSecurePrefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val all = encPrefs.all
            if (all.isNotEmpty()) {
                Log.d(TAG, "Migrating ${all.size} items from EncryptedSharedPreferences to direct Android KeyStore")
                for ((key, value) in all) {
                    if (value != null) {
                        if (key in SECRET_KEYS) {
                            if (value is String) putSecretString(key, value)
                        } else {
                            when (value) {
                                is String -> {
                                    val plain = if (value.startsWith("enc:v1:")) KeyStoreCryptoHelper.decrypt(value) else value
                                    putPlainString(key, plain)
                                }
                                is Boolean -> prefs.edit().putBoolean(key, value).apply()
                                is Int -> prefs.edit().putInt(key, value).apply()
                                is Long -> prefs.edit().putLong(key, value).apply()
                                is Float -> prefs.edit().putFloat(key, value).apply()
                                is Set<*> -> @Suppress("UNCHECKED_CAST") prefs.edit().putStringSet(key, value as Set<String>).apply()
                            }
                        }
                    }
                }
                encPrefs.edit().clear().apply()
                Log.d(TAG, "Migration from EncryptedSharedPreferences complete and old file cleared")
            }
        } catch (e: Throwable) {
            // No legacy EncryptedSharedPreferences or already migrated
        }
    }

    private fun migrateFromLegacyPlaintext(context: Context) {
        try {
            val legacy = context.getSharedPreferences("WhisperRemotePrefs", Context.MODE_PRIVATE)
            val all = legacy.all
            if (all.isNotEmpty()) {
                Log.d(TAG, "Migrating ${all.size} items from legacy plaintext to direct Android KeyStore")
                for ((key, value) in all) {
                    if (!prefs.contains(key) && value != null) {
                        if (key in SECRET_KEYS) {
                            if (value is String) putSecretString(key, value)
                        } else {
                            when (value) {
                                is String -> putPlainString(key, value)
                                is Boolean -> prefs.edit().putBoolean(key, value).apply()
                                is Int -> prefs.edit().putInt(key, value).apply()
                                is Long -> prefs.edit().putLong(key, value).apply()
                                is Float -> prefs.edit().putFloat(key, value).apply()
                                is Set<*> -> @Suppress("UNCHECKED_CAST") prefs.edit().putStringSet(key, value as Set<String>).apply()
                            }
                        }
                    }
                }
                legacy.edit().clear().apply()
                Log.d(TAG, "Legacy plaintext preferences migrated and file cleared")
            }
        } catch (e: Throwable) {
            // Ignore
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "WhisperUnchainedPrefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LEGACY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model_name"
        private const val KEY_PROMPT_HINTS = "prompt_hints"
        private const val KEY_AUTO_TRIM_HINTS = "auto_trim_hints"
        private const val KEY_INJECTION_MODE = "injection_mode"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_AUTO_RESTART_TAILSCALE = "auto_restart_tailscale"
        private const val KEY_PRESET = "provider_preset"
        private const val KEY_PROCESSING_MODE = "processing_mode"

        const val DEFAULT_MODEL = "large-v3-turbo"

        val SECRET_KEYS = setOf(KEY_API_KEY, "cleanup_chat_key")

        /**
         * Validates and parses the QR pairing JSON payload according to contract F-12.
         * Enforces: version 1, scheme http or https, non-empty host, and path ending with /v1.
         * Returns a valid QrPayload if compliant, null otherwise.
         */
        fun parseAndValidateQrPayload(jsonStr: String): QrPayload? {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                return try {
                    val uri = java.net.URI(trimmed)
                    val host = uri.host
                    if (host.isNullOrBlank()) return null
                    var cleanUrl = trimmed
                    if (!cleanUrl.endsWith("/v1")) {
                        cleanUrl = if (cleanUrl.endsWith("/")) "${cleanUrl}v1" else "$cleanUrl/v1"
                    }
                    val hostDisplay = if (uri.port != -1) "$host:${uri.port}" else host
                    QrPayload(
                        baseUrl = cleanUrl,
                        apiKey = null,
                        model = null,
                        name = "Your PC",
                        hostDisplay = hostDisplay
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse raw URL QR code", e)
                    null
                }
            }

            return try {
                val json = JSONObject(jsonStr)
                val version = json.optInt("v", -1)
                if (version != 1) {
                    Log.w(TAG, "Unsupported QR payload version: $version")
                    return null
                }

                var importedBaseUrl = json.optString("base_url", "").trim()
                if (importedBaseUrl.isBlank()) {
                    return null
                }

                val uri = try {
                    java.net.URI(importedBaseUrl)
                } catch (e: Exception) {
                    null
                }
                if (uri == null) {
                    Log.w(TAG, "Malformed URI in QR base_url: $importedBaseUrl")
                    return null
                }
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    Log.w(TAG, "Invalid QR base_url scheme: $scheme")
                    return null
                }

                val host = uri.host
                if (host.isNullOrBlank()) {
                    Log.w(TAG, "Invalid QR base_url host: $host")
                    return null
                }

                // Ensure path ends with /v1 (or append it)
                if (!importedBaseUrl.endsWith("/v1")) {
                    importedBaseUrl = if (importedBaseUrl.endsWith("/")) {
                        "${importedBaseUrl}v1"
                    } else {
                        "$importedBaseUrl/v1"
                    }
                }

                val hostDisplay = if (uri.port != -1) "$host:${uri.port}" else host
                val key = if (json.has("api_key") && !json.isNull("api_key")) json.getString("api_key") else null
                val model = if (json.has("model") && !json.isNull("model")) json.getString("model") else null
                val name = if (json.has("name") && !json.isNull("name")) json.getString("name") else null

                QrPayload(
                    baseUrl = importedBaseUrl,
                    apiKey = key,
                    model = model,
                    name = name,
                    hostDisplay = hostDisplay
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse QR payload", e)
                null
            }
        }
    }

    /**
     * Retrieves the base URL (ending in /v1 for OpenAI compatibility).
     * If legacy "api_url" exists (e.g. ".../transcribe"), it automatically migrates it.
     * Never returns a fake demo IP; returns "" if unconfigured so hint is displayed.
     */
    var baseUrl: String
        get() {
            val savedBase = getPlainString(KEY_BASE_URL, "")
            if (savedBase.isNotBlank() && !savedBase.contains("100.x.y.z") && !savedBase.contains("your-server-ip")) {
                return savedBase.trimEnd('/')
            }

            val legacy = getPlainString(KEY_LEGACY_API_URL, "")
            if (legacy.isNotBlank() && !legacy.contains("your-server-ip") && !legacy.contains("100.x.y.z")) {
                val cleaned = if (legacy.endsWith("/transcribe")) {
                    legacy.removeSuffix("/transcribe") + "/v1"
                } else if (!legacy.endsWith("/v1")) {
                    legacy.trimEnd('/') + "/v1"
                } else {
                    legacy
                }
                baseUrl = cleaned
                return cleaned.trimEnd('/')
            }

            return if (savedBase.isNotBlank() && !savedBase.contains("100.x.y.z") && !savedBase.contains("your-server-ip")) savedBase.trimEnd('/') else ""
        }
        set(value) {
            val clean = value.trim().trimEnd('/')
            putPlainString(KEY_BASE_URL, clean)
            putPlainString(KEY_LEGACY_API_URL, clean)
        }

    var apiKey: String
        get() = getSecretString(KEY_API_KEY, "")
        set(value) = putSecretString(KEY_API_KEY, value.trim())

    var modelName: String
        get() = getPlainString(KEY_MODEL, DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(value) = putPlainString(KEY_MODEL, value.trim())

    var promptHints: String
        get() = getPlainString(KEY_PROMPT_HINTS, "")
        set(value) = putPlainString(KEY_PROMPT_HINTS, value.trim())

    var autoTrimHints: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRIM_HINTS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TRIM_HINTS, value).apply()

    var injectionMode: InjectionMode
        get() {
            val name = getPlainString(KEY_INJECTION_MODE, InjectionMode.ACCESSIBILITY.name)
            return try {
                InjectionMode.valueOf(name)
            } catch (e: Exception) {
                InjectionMode.ACCESSIBILITY
            }
        }
        set(value) = putPlainString(KEY_INJECTION_MODE, value.name)

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var autoRestartTailscale: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTART_TAILSCALE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTART_TAILSCALE, value).apply()

    // Reason for edit: Track whether connection has been tested/verified for setup checklist per contract F-16
    var isServerVerified: Boolean
        get() = prefs.getBoolean("server_verified", false)
        set(value) = prefs.edit().putBoolean("server_verified", value).apply()

    var isSetupDismissedManually: Boolean
        get() = prefs.getBoolean("setup_dismissed_manually", false)
        set(value) = prefs.edit().putBoolean("setup_dismissed_manually", value).apply()

    // Reason for edit: Added hasCompletedFirstRunChecklist per contracts/04-look-and-feel.md §4
    var hasCompletedFirstRunChecklist: Boolean
        get() = prefs.getBoolean("has_completed_first_run_checklist", false) || isSetupDismissedManually
        set(value) {
            prefs.edit().putBoolean("has_completed_first_run_checklist", value).apply()
            if (value) isSetupDismissedManually = true
        }

    var providerPreset: ProviderPreset
        get() {
            val name = getPlainString(KEY_PRESET, ProviderPreset.SELF_HOSTED.name)
            return ProviderPreset.fromLegacy(name)
        }
        set(value) = putPlainString(KEY_PRESET, value.name)

    var processingMode: String
        get() = getPlainString(KEY_PROCESSING_MODE, "full").ifBlank { "full" }
        set(value) = putPlainString(KEY_PROCESSING_MODE, value)

    // Reason for edit: Added dictation cleanup style and endpoint preferences per contracts/02-cleanup.md
    var cleanupEnabled: Boolean
        get() = prefs.getBoolean("cleanup_enabled", false)
        set(value) = prefs.edit().putBoolean("cleanup_enabled", value).apply()

    // Reason for edit: Track whether user explicitly toggled cleanup switch per contract K-10
    var cleanupUserSet: Boolean
        get() = prefs.getBoolean("cleanup_user_set", false)
        set(value) = prefs.edit().putBoolean("cleanup_user_set", value).apply()

    fun syncSmartCleanupDefault(cleanupAlwaysFromServer: Boolean? = null) {
        if (cleanupUserSet) return
        if (providerPreset == ProviderPreset.SELF_HOSTED) {
            if (cleanupAlwaysFromServer != null) {
                cleanupEnabled = cleanupAlwaysFromServer
            }
        } else {
            val hasChatModel = cleanupChatUrl.isNotBlank() && cleanupChatKey.isNotBlank()
            cleanupEnabled = hasChatModel
        }
    }

    var cleanupPunctuation: String
        get() = getPlainString("cleanup_punctuation", "standard").ifBlank { "standard" }
        set(value) = putPlainString("cleanup_punctuation", value)

    var cleanupRewrite: String
        get() = getPlainString("cleanup_rewrite", "keep").ifBlank { "keep" }
        set(value) = putPlainString("cleanup_rewrite", value)

    // Reason for edit: Added cleanup_tone per contracts/02-cleanup.md v2 (K-5)
    var cleanupTone: String
        get() = getPlainString("cleanup_tone", "neutral").ifBlank { "neutral" }
        set(value) = putPlainString("cleanup_tone", value)

    var cleanupShorten: String
        get() = getPlainString("cleanup_shorten", "off").ifBlank { "off" }
        set(value) = putPlainString("cleanup_shorten", value)

    var cleanupEmoji: String
        get() = getPlainString("cleanup_emoji", "off").ifBlank { "off" }
        set(value) = putPlainString("cleanup_emoji", value)

    var cleanupChatUrl: String
        get() = getPlainString("cleanup_chat_url", "https://api.openai.com/v1/chat/completions")
        set(value) = putPlainString("cleanup_chat_url", value.trim())

    var cleanupChatKey: String
        get() = getSecretString("cleanup_chat_key", "")
        set(value) = putSecretString("cleanup_chat_key", value.trim())

    var cleanupChatModel: String
        get() = getPlainString("cleanup_chat_model", "gpt-4o-mini").ifBlank { "gpt-4o-mini" }
        set(value) = putPlainString("cleanup_chat_model", value.trim())

    var cleanupChatPreset: ChatProviderPreset
        get() {
            val name = getPlainString("cleanup_chat_preset", ChatProviderPreset.GROQ.name)
            return ChatProviderPreset.fromLegacy(name)
        }
        set(value) = putPlainString("cleanup_chat_preset", value.name)

    /**
     * Resolves the full URL for POST /v1/audio/transcriptions
     */
    fun getTranscribeUrl(): String {
        val base = baseUrl
        if (base.isBlank()) return ""
        return if (base.endsWith("/v1")) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
    }

    /**
     * Resolves the GET /health URL by stripping /v1 from the base URL.
     */
    fun getHealthUrl(): String {
        val base = baseUrl
        if (base.isBlank()) return ""
        val root = if (base.endsWith("/v1")) {
            base.substring(0, base.length - 3)
        } else {
            base
        }
        return "${root.trimEnd('/')}/health"
    }

    /**
     * Resolves the GET /v1/models URL for connection testing.
     */
    fun getModelsUrl(): String {
        val base = baseUrl
        if (base.isBlank()) return ""
        return if (base.endsWith("/v1")) {
            "$base/models"
        } else {
            "$base/v1/models"
        }
    }

    /**
     * Resolves the GET / POST /v1/vocabulary URL per contracts/01-transcription-api.md §8.
     * Reason for edit: Added shared vocabulary endpoint resolver and offline sync queue.
     */
    fun getVocabularyUrl(): String {
        val base = baseUrl
        return if (base.endsWith("/v1")) {
            "$base/vocabulary"
        } else {
            "$base/v1/vocabulary"
        }
    }

    var pendingVocabularyQueue: Set<String>
        get() = prefs.getStringSet("pending_vocab_queue", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("pending_vocab_queue", value).apply()

    // Reason for edit: Added local name corrections for cloud presets per contracts/04 §6
    var localNameCorrections: String
        get() = getPlainString("local_name_corrections", "")
        set(value) = putPlainString("local_name_corrections", value.trim())

    // Reason for edit: On-device transcription configuration & offline vocabulary cache per contract 05 §2
    var cachedServerVocabulary: Set<String>
        get() = prefs.getStringSet("cached_server_vocabulary", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("cached_server_vocabulary", value).apply()

    var cachedServerNameCorrectionsJson: String
        get() = getPlainString("cached_server_name_corrections_json", "")
        set(value) = putPlainString("cached_server_name_corrections_json", value)

    var removeFillerSounds: Boolean
        get() = prefs.getBoolean("remove_filler_sounds", false)
        set(value) = prefs.edit().putBoolean("remove_filler_sounds", value).apply()

    var transcriptionTarget: String
        get() = getPlainString("transcription_target", TranscriptionTarget.PC.id)
        set(value) = putPlainString("transcription_target", value)

    var fallbackToOnDevice: Boolean
        get() = prefs.getBoolean("fallback_to_on_device", true)
        set(value) = prefs.edit().putBoolean("fallback_to_on_device", value).apply()

    var selectedOnDeviceModelId: String
        get() = getPlainString("selected_on_device_model_id", "parakeet-tdt-110m-en-int8")
        set(value) = putPlainString("selected_on_device_model_id", value)

    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean("download_wifi_only", true)
        set(value) = prefs.edit().putBoolean("download_wifi_only", value).apply()

    fun queueVocabularyAddition(word: String) {
        val clean = word.trim()
        if (clean.isNotEmpty() && clean.length <= 60 && !clean.contains(',') && !clean.contains('\n')) {
            val current = pendingVocabularyQueue.toMutableSet()
            current.add(clean)
            pendingVocabularyQueue = current
        }
    }

    fun clearPendingVocabulary(wordsToRemove: Collection<String>) {
        val current = pendingVocabularyQueue.toMutableSet()
        current.removeAll(wordsToRemove.toSet())
        pendingVocabularyQueue = current
    }

    /**
     * Validates and parses the QR pairing JSON payload according to contract F-12.
     * Delegates to Companion.parseAndValidateQrPayload.
     */
    fun parseAndValidateQrPayload(jsonStr: String): QrPayload? = Companion.parseAndValidateQrPayload(jsonStr)

    /**
     * Applies a validated QrPayload to persistent configuration per contract F-12.
     */
    fun applyQrPayload(payload: QrPayload) {
        baseUrl = payload.baseUrl
        payload.apiKey?.let { apiKey = it }
        payload.model?.let { modelName = it }
        providerPreset = ProviderPreset.SELF_HOSTED
        isServerVerified = true
    }

    /**
     * Imports configuration from the QR payload generated by the Desktop server installer.
     * Schema: { "v": 1, "base_url": "http://100.x.y.z:5000/v1", "api_key": "...", "model": "...", "name": "Head PC" }
     * Returns true if parsed and imported successfully.
     */
    fun importQrPayload(jsonStr: String): Boolean {
        val payload = parseAndValidateQrPayload(jsonStr) ?: return false
        applyQrPayload(payload)
        return true
    }
}
