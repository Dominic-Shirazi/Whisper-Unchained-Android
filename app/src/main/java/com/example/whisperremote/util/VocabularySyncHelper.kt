// This file provides utility functions for synchronizing, parsing, and deduplicating vocabulary words.
// It is called by MainActivity during vocabulary sync and SettingsRepository initialization.
// Inputs: Comma/newline-separated vocabulary text strings, server vocabulary lists, and name correction hint lists.
// Outputs: Cleaned, deduplicated word lists, newly detected local words to push to POST /v1/vocabulary, and formatted hint strings.
// It ensures two-way sync between phone and desktop server per contracts/01 §8 without clobbering local additions.

package com.example.whisperremote.util

// Reason for edit: Created to support true two-way vocabulary sync (deduplication + push) per user request and contract 01 §8.

object VocabularySyncHelper {

    const val MAX_WORD_LENGTH = 60
    const val MAX_WORDS_PER_PUSH = 50

    /**
     * Splits text by commas, newlines, or semicolons, trims whitespace, and deduplicates case-insensitively.
     */
    fun parseWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    /**
     * Identifies newly typed local words that are not present on the server or in name corrections.
     * Capped at 50 words and 60 chars per word per contracts/01 §8.
     */
    fun findNewWords(
        localWords: List<String>,
        serverVocab: List<String>,
        fromNameCorrections: List<String> = emptyList()
    ): List<String> {
        val serverSet = (serverVocab + fromNameCorrections)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        return localWords
            .filter { it.trim().lowercase() !in serverSet && it.trim().length <= MAX_WORD_LENGTH }
            .distinctBy { it.trim().lowercase() }
            .take(MAX_WORDS_PER_PUSH)
    }

    /**
     * Merges server vocabulary and local words into a single list, deduplicating case-insensitively.
     * Preserves server words first, followed by any new local words.
     */
    fun mergeAndDeduplicate(
        serverVocab: List<String>,
        localWords: List<String>
    ): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (word in serverVocab) {
            val trimmed = word.trim()
            if (trimmed.isNotBlank() && seen.add(trimmed.lowercase())) {
                result.add(trimmed)
            }
        }

        for (word in localWords) {
            val trimmed = word.trim()
            if (trimmed.isNotBlank() && seen.add(trimmed.lowercase())) {
                result.add(trimmed)
            }
        }

        return result
    }

    /**
     * Formats a list of words as a comma-separated string suitable for the hints text field.
     */
    fun formatForDisplay(words: List<String>): String {
        return words.joinToString(", ")
    }
}
