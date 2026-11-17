// This file defines the data model for built-in voice trigger phrases.
// It is called by ApiClient, TriggersScreen, and SettingsRepository.
// Inputs: JSON from GET /v1/trigger_phrases or user inputs from TriggersScreen dialogs.
// Outputs: Structured TriggerPhrase instances with phrase text and action ("edit" or "end").
// Conforms to contracts/01-transcription-api.md §13 and F-60.

package com.example.whisperremote.data

// Reason for creation: Built-in trigger phrase data model and JSON serializers per contract 01 §13 and F-60.

import org.json.JSONObject

data class TriggerPhrase(
    val phrase: String,
    val action: String // "edit" (Starts an AI edit) or "end" (Ends the instruction)
) {
    fun actionLabel(): String {
        return when (action.lowercase()) {
            "edit" -> "Starts an AI edit"
            "end" -> "Ends the instruction"
            else -> action
        }
    }

    fun isEdit(): Boolean = action.equals("edit", ignoreCase = true)
    fun isEnd(): Boolean = action.equals("end", ignoreCase = true)

    fun toJson(): JSONObject = JSONObject().apply {
        put("phrase", phrase)
        put("action", action)
    }

    companion object {
        fun fromJson(json: JSONObject): TriggerPhrase {
            return TriggerPhrase(
                phrase = json.optString("phrase", "").trim(),
                action = json.optString("action", "edit").trim()
            )
        }
    }
}
