// This file validates helper words and name corrections ("heard as" -> "write as").
// It is called by WordsTriggersScreen and mutation handlers before sending requests or queueing edits.
// Inputs: User-entered helper word strings and name correction pairs (heard as, write as), plus existing collections for duplicate detection.
// Outputs: Returns null if valid, or a human-readable error message if invalid.
// Conforms to contracts/01-transcription-api.md §8 and F-43.
// Reason for edit: Add duplicate checking and exact contract validation per F-43.

package com.example.whisperremote.util

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
     * Validates a name correction pair according to contract 01 §8.
     * Rules: heard-as at least 2 characters, each side under 60 characters,
     * no commas or line breaks, sides not identical (case-insensitive), neither side blank,
     * no duplicate heard-as.
     */
    fun validateNameCorrection(
        heard: String,
        write: String,
        existingHeardList: Collection<String> = emptyList(),
        currentHeard: String? = null
    ): String? {
        val h = heard.trim()
        val w = write.trim()
        if (h.length < 2) return "Heard as must be at least 2 characters"
        if (w.isEmpty()) return "Write as cannot be empty"
        if (h.length >= 60 || w.length >= 60) return "Each side must be under 60 characters"
        if (h.contains(",") || h.contains("\n") || h.contains("\r") ||
            w.contains(",") || w.contains("\n") || w.contains("\r")) {
            return "Commas and line breaks are not allowed"
        }
        if (h.equals(w, ignoreCase = true)) {
            return "Heard as and Write as cannot be identical"
        }
        if (existingHeardList.any { it.equals(h, ignoreCase = true) && !it.equals(currentHeard?.trim(), ignoreCase = true) }) {
            return "A correction for '$h' already exists"
        }
        return null
    }
}
