// This file defines the custom trigger data structures and limits.
// It is called by ApiClient, WordsTriggersScreen, and FloatingButtonService.
// Inputs: JSON from GET /v1/custom_triggers, library.json, or user input from Custom triggers screen.
// Outputs: Validated trigger objects, trigger limit specifications, and suggestion scoring.
// Conforms to contracts/01-transcription-api.md §11 & §12.

package com.example.whisperremote.data

// Reason for creation: Custom trigger model, limits, and shared library model per contract 01 §11 & F-53.

import org.json.JSONArray
import org.json.JSONObject

data class CustomTrigger(
    val id: String,
    val name: String,
    val phrases: List<String>,
    val prompt: String,
    val enabled: Boolean = true,
    val libraryId: String? = null,
    val libraryVersion: Int? = null,
    val author: String? = null,
    val temperature: Double? = null,
    val description: String? = null,
    val category: String? = null,
    val model: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("phrases", JSONArray(phrases))
            put("prompt", prompt)
            put("enabled", enabled)
            if (!libraryId.isNullOrBlank()) put("library_id", libraryId)
            if (libraryVersion != null) put("library_version", libraryVersion)
            if (!author.isNullOrBlank()) put("author", author)
            if (temperature != null) put("temperature", temperature)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CustomTrigger {
            val phrasesArray = json.optJSONArray("phrases") ?: JSONArray()
            val phrasesList = (0 until phrasesArray.length()).map { phrasesArray.getString(it) }
            return CustomTrigger(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                phrases = phrasesList,
                prompt = json.optString("prompt", ""),
                enabled = json.optBoolean("enabled", true),
                libraryId = if (json.has("library_id") && !json.isNull("library_id")) json.getString("library_id") else if (json.has("id") && !json.isNull("id")) json.getString("id") else null,
                libraryVersion = if (json.has("library_version") && !json.isNull("library_version")) json.getInt("library_version") else if (json.has("version") && !json.isNull("version")) json.getInt("version") else null,
                author = if (json.has("author") && !json.isNull("author")) json.getString("author") else null,
                temperature = if (json.has("temperature") && !json.isNull("temperature")) json.getDouble("temperature") else null,
                description = if (json.has("description") && !json.isNull("description")) json.getString("description") else null,
                category = if (json.has("category") && !json.isNull("category")) json.getString("category") else null,
                model = if (json.has("model") && !json.isNull("model")) json.getString("model") else null
            )
        }
    }
}

data class TriggerLimits(
    val triggers: Int = 100,
    val nameMax: Int = 40,
    val phrasesMax: Int = 6,
    val phraseMax: Int = 60,
    val promptMin: Int = 10,
    val promptMax: Int = 6000
) {
    companion object {
        fun fromJson(json: JSONObject?): TriggerLimits {
            if (json == null) return TriggerLimits()
            return TriggerLimits(
                triggers = json.optInt("triggers", 100),
                nameMax = json.optInt("name", 40),
                phrasesMax = json.optInt("phrases", 6),
                phraseMax = json.optInt("phrase", 60),
                promptMin = json.optInt("prompt_min", 10),
                promptMax = json.optInt("prompt", 6000)
            )
        }
    }
}

data class CustomTriggersResponse(
    val triggers: List<CustomTrigger>,
    val limits: TriggerLimits
)
