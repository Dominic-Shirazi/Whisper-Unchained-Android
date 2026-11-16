// This file validates helper words and corrections & shortcuts ("When I say" -> "Type this").
// It is called by WordsTriggersScreen and mutation handlers before sending requests or queueing edits.
// Inputs: User-entered helper word strings and name/shortcut correction pairs (heard as, write as), plus existing collections for duplicate detection.
// Outputs: Returns null if valid, or a human-readable error message if invalid.
// Conforms to contracts/01-transcription-api.md §8, F-43, and F-50.

package com.example.whisperremote.util

// Reason for edit: Relax "Type this" validation to 200 characters and allow commas per contract 01 §8 and F-50.

object WordRulesValidator {

    /**
     * Validates a helper word according to contract 01 §8.
     * Rules: non-blank, under 60 characters, no commas, no line breaks, not duplicate.
     */
    fun validateHelperWord(
        word: String,
        existingWords: Collection<String> = emptyList(),
        currentWord: String? = null
    ): String? {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return "Word cannot be empty"
        if (trimmed.length >= 60) return "Word must be under 60 characters"
        if (trimmed.contains(",") || trimmed.contains("\n") || trimmed.contains("\r")) {
            return "Commas and line breaks are not allowed"
        }
        if (existingWords.any { it.equals(trimmed, ignoreCase = true) && !it.equals(currentWord?.trim(), ignoreCase = true) }) {
            return "Word '$trimmed' is already in your vocabulary"
        }
        return null
    }

    /**
     * Validates a correction/shortcut pair according to contract 01 §8 and F-50.
     * Rules:
     * - "When I say": 2–59 characters (length in 2..59), no commas, no line breaks.
     * - "Type this": 1–200 characters (length in 1..200), commas allowed, no line breaks.
     * - Sides not identical (case-insensitive).
     * - No duplicate "When I say".
     */
    fun validateNameCorrection(
        heard: String,
        write: String,
        existingHeardList: Collection<String> = emptyList(),
        currentHeard: String? = null
    ): String? {
        val h = heard.trim()
        val w = write.trim()
        if (h.length < 2) return "\"When I say\" must be at least 2 characters"
        if (h.length >= 60) return "\"When I say\" must be under 60 characters"
        if (h.contains(",") || h.contains("\n") || h.contains("\r")) {
            return "Commas and line breaks are not allowed in \"When I say\""
        }

        if (w.isEmpty()) return "\"Type this\" cannot be empty"
        if (w.length > 200) return "\"Type this\" must be at most 200 characters"
        if (w.contains("\n") || w.contains("\r")) {
            return "Line breaks are not allowed in \"Type this\""
        }

        if (h.equals(w, ignoreCase = true)) {
            return "\"When I say\" and \"Type this\" cannot be identical"
        }
        if (existingHeardList.any { it.equals(h, ignoreCase = true) && !it.equals(currentHeard?.trim(), ignoreCase = true) }) {
            return "A correction for '$h' already exists"
        }
        return null
    }
}
