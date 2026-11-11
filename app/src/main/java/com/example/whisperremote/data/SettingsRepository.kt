// This file manages persistent configuration for Whisper Unchained.
// It is called by MainActivity, FloatingButtonService, and ApiClient.
// Inputs: User inputs from Settings UI or QR code scan, backed by hardware-backed KeyStore AES-256-GCM encryption.
// Outputs: Provider settings (base URL, API key, model, prompt hints), behavioral flags (auto-trim, boot start, Tailscale auto-restart), and injection mode.
// It resolves endpoints according to the OpenAI standard (/v1/audio/transcriptions, /health, /v1/models) and migrates legacy URLs cleanly.

package com.example.whisperremote.data

// Reason for edit: Migrated from deprecated androidx.security:security-crypto to direct Android KeyStore AES-256-GCM cipher helper per F-4, with transparent one-time migration.

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.whisperremote.util.KeyStoreCryptoHelper
import org.json.JSONObject

enum class InjectionMode {
    ACCESSIBILITY,
    CLIPBOARD
}

enum class ProviderPreset(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    // Reason for edit: Set SELF_HOSTED defaultBaseUrl to empty so it never clobbers user server URLs with dummy IPs; Groq default updated to whisper-large-v3-turbo per contracts/04 (K-6).
    SELF_HOSTED("Self-Hosted", "", "large-v3-turbo"),
    GROQ("Groq Cloud", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo"),
    OPENROUTER_OPENAI("OpenAI / OpenRouter", "https://api.openai.com/v1", "whisper-1"),
    CUSTOM("Custom Endpoint", "", "");

    companion object {
        fun fromLegacy(name: String?): ProviderPreset {
            return when (name) {
                "HEAD_PC" -> SELF_HOSTED
                "OLLAMA" -> CUSTOM
                else -> values().find { it.name == name } ?: SELF_HOSTED
            }
        }
    }
}

// Reason for edit: Added chat presets for cloud dictation cleanup per Q-2
enum class ChatProviderPreset(val displayName: String, val defaultUrl: String, val defaultModel: String) {
    GROQ("Groq (llama-3.3-70b)", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
    OPENROUTER_OPENAI("OpenAI (gpt-4o-mini)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    OLLAMA("Ollama / LocalAI (Tailscale/LAN)", "http://100.x.y.z:11434/v1/chat/completions", "llama3.2"),
    CUSTOM("Custom Endpoint", "", "")
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isHardwareEncrypted: Boolean
        get() = KeyStoreCryptoHelper.isHardwareAvailable()

    init {
        migrateFromEncryptedSharedPreferences(context)
        migrateFromLegacyPlaintext(context)
    }

    private fun putEncryptedString(key: String, value: String) {
        val cipher = KeyStoreCryptoHelper.encrypt(value)
        prefs.edit().putString(key, cipher).apply()
    }

    private fun getEncryptedString(key: String, defaultValue: String = ""): String {
        val raw = prefs.getString(key, null) ?: return defaultValue
        return KeyStoreCryptoHelper.decrypt(raw)
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
                    if (!prefs.contains(key) && value != null) {
                        when (value) {
                            is String -> putEncryptedString(key, value)
                            is Boolean -> prefs.edit().putBoolean(key, value).apply()
                            is Int -> prefs.edit().putInt(key, value).apply()
                            is Long -> prefs.edit().putLong(key, value).apply()
                            is Float -> prefs.edit().putFloat(key, value).apply()
                            is Set<*> -> @Suppress("UNCHECKED_CAST") prefs.edit().putStringSet(key, value as Set<String>).apply()
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
                        when (value) {
                            is String -> putEncryptedString(key, value)
                            is Boolean -> prefs.edit().putBoolean(key, value).apply()
                            is Int -> prefs.edit().putInt(key, value).apply()
                            is Long -> prefs.edit().putLong(key, value).apply()
                            is Float -> prefs.edit().putFloat(key, value).apply()
                            is Set<*> -> @Suppress("UNCHECKED_CAST") prefs.edit().putStringSet(key, value as Set<String>).apply()
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
    }

    /**
     * Retrieves the base URL (ending in /v1 for OpenAI compatibility).
     * If legacy "api_url" exists (e.g. ".../transcribe"), it automatically migrates it.
     * Never returns a fake demo IP; returns "" if unconfigured so hint is displayed.
     */
    var baseUrl: String
        get() {
            val savedBase = getEncryptedString(KEY_BASE_URL, "")
            if (savedBase.isNotBlank() && !savedBase.contains("100.x.y.z") && !savedBase.contains("your-server-ip")) {
                return savedBase.trimEnd('/')
            }

            val legacy = getEncryptedString(KEY_LEGACY_API_URL, "")
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
            putEncryptedString(KEY_BASE_URL, clean)
            putEncryptedString(KEY_LEGACY_API_URL, clean)
        }

    var apiKey: String
        get() = getEncryptedString(KEY_API_KEY, "")
        set(value) = putEncryptedString(KEY_API_KEY, value.trim())

    var modelName: String
        get() = getEncryptedString(KEY_MODEL, DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(value) = putEncryptedString(KEY_MODEL, value.trim())

    var promptHints: String
        get() = getEncryptedString(KEY_PROMPT_HINTS, "")
        set(value) = putEncryptedString(KEY_PROMPT_HINTS, value.trim())

    var autoTrimHints: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRIM_HINTS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TRIM_HINTS, value).apply()

    var injectionMode: InjectionMode
        get() {
            val name = prefs.getString(KEY_INJECTION_MODE, InjectionMode.ACCESSIBILITY.name)
            return try {
                InjectionMode.valueOf(name ?: InjectionMode.ACCESSIBILITY.name)
            } catch (e: Exception) {
                InjectionMode.ACCESSIBILITY
            }
        }
        set(value) = prefs.edit().putString(KEY_INJECTION_MODE, value.name).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var autoRestartTailscale: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTART_TAILSCALE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTART_TAILSCALE, value).apply()

    var providerPreset: ProviderPreset
        get() {
            val name = prefs.getString(KEY_PRESET, ProviderPreset.SELF_HOSTED.name)
            return ProviderPreset.fromLegacy(name)
        }
        set(value) = prefs.edit().putString(KEY_PRESET, value.name).apply()

    var processingMode: String
        get() = prefs.getString(KEY_PROCESSING_MODE, "full") ?: "full"
        set(value) = prefs.edit().putString(KEY_PROCESSING_MODE, value).apply()

    // Reason for edit: Added dictation cleanup style and endpoint preferences per contracts/02-cleanup.md
    var cleanupEnabled: Boolean
        get() = prefs.getBoolean("cleanup_enabled", false)
        set(value) = prefs.edit().putBoolean("cleanup_enabled", value).apply()

    var cleanupPunctuation: String
        get() = prefs.getString("cleanup_punctuation", "standard") ?: "standard"
        set(value) = prefs.edit().putString("cleanup_punctuation", value).apply()

    var cleanupRewrite: String
        get() = prefs.getString("cleanup_rewrite", "keep") ?: "keep"
        set(value) = prefs.edit().putString("cleanup_rewrite", value).apply()

    // Reason for edit: Added cleanup_tone per contracts/02-cleanup.md v2 (K-5)
    var cleanupTone: String
        get() = prefs.getString("cleanup_tone", "neutral") ?: "neutral"
        set(value) = prefs.edit().putString("cleanup_tone", value).apply()

    var cleanupShorten: String
        get() = prefs.getString("cleanup_shorten", "off") ?: "off"
        set(value) = prefs.edit().putString("cleanup_shorten", value).apply()

    var cleanupEmoji: String
        get() = prefs.getString("cleanup_emoji", "off") ?: "off"
        set(value) = prefs.edit().putString("cleanup_emoji", value).apply()

    var cleanupChatUrl: String
        get() = getEncryptedString("cleanup_chat_url", "https://api.openai.com/v1/chat/completions")
        set(value) = putEncryptedString("cleanup_chat_url", value.trim())

    var cleanupChatKey: String
        get() = getEncryptedString("cleanup_chat_key", "")
        set(value) = putEncryptedString("cleanup_chat_key", value.trim())

    var cleanupChatModel: String
        get() = getEncryptedString("cleanup_chat_model", "gpt-4o-mini").ifBlank { "gpt-4o-mini" }
        set(value) = putEncryptedString("cleanup_chat_model", value.trim())

    var cleanupChatPreset: ChatProviderPreset
        get() {
            val name = prefs.getString("cleanup_chat_preset", ChatProviderPreset.OPENROUTER_OPENAI.name)
            return ChatProviderPreset.values().find { it.name == name } ?: ChatProviderPreset.OPENROUTER_OPENAI
        }
        set(value) = prefs.edit().putString("cleanup_chat_preset", value.name).apply()

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
        get() = getEncryptedString("local_name_corrections", "")
        set(value) = putEncryptedString("local_name_corrections", value.trim())

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
     * Imports configuration from the QR payload generated by the Desktop server installer.
     * Schema: { "v": 1, "base_url": "http://100.x.y.z:5000/v1", "api_key": "...", "model": "...", "name": "Head PC" }
     * Returns true if parsed and imported successfully.
     */
    fun importQrPayload(jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val version = json.optInt("v", -1)
            if (version != 1) {
                Log.w("SettingsRepository", "Unsupported QR payload version: $version")
                return false
            }

            val importedBaseUrl = json.optString("base_url", "")
            if (importedBaseUrl.isBlank()) {
                return false
            }

            baseUrl = importedBaseUrl
            if (json.has("api_key")) {
                apiKey = json.getString("api_key")
            }
            if (json.has("model")) {
                modelName = json.getString("model")
            }
            providerPreset = ProviderPreset.SELF_HOSTED
            true
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Failed to parse QR payload", e)
            false
        }
    }
}
