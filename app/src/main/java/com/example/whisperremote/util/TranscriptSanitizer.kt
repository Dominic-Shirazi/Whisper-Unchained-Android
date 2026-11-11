// This file provides text sanitization for transcribed speech.
// It is called by FloatingButtonService before injecting or copying transcribed text, and ApiClient when preparing hints.
// Inputs: rawText (String from Whisper API), promptHints (String containing comma-separated hint keywords).
// Output: sanitized String with leading hint recitations and trailing echo loops stripped per contracts/01 §10.
// Whisper models can regurgitate prompt words during silence; this module removes only confirmed echo loops.

package com.example.whisperremote.util

// Reason for edit: Implemented strict hint echo removal per contracts/01 §10 (K-6): only strips when trailing run is entirely hint words AND at least one repeats 3+ times; preserves single hint words like "Let's ask Ollama".

object TranscriptSanitizer {

    const val MAX_HINT_CHARS = 540

    /**
     * Clamps prompt hints to Whisper's effective token window (~540 chars / 224 tokens).
     * Truncates at whole word boundaries to avoid generating partial-word hallucinations.
     */
    fun clampHints(promptHints: String): String {
        if (promptHints.length <= MAX_HINT_CHARS) {
            return promptHints.trim()
        }
        val sub = promptHints.substring(0, MAX_HINT_CHARS)
        val lastComma = sub.lastIndexOf(',')
        return if (lastComma > 0) {
            sub.substring(0, lastComma).trim()
        } else {
            val lastSpace = sub.lastIndexOf(' ')
            if (lastSpace > 0) sub.substring(0, lastSpace).trim() else sub.trim()
        }
    }

    /**
     * Sanitizes [rawText] for cloud presets per contracts/01 §10:
     * 1. Stripping leading recitation of hint terms (when Whisper regurgitates 3+ hints in order at start).
     * 2. Stripping trailing hint echo ONLY if all trailing words are hints AND at least one repeats 3+ times.
     */
    fun sanitize(rawText: String, promptHints: String): String {
        if (rawText.isBlank() || promptHints.isBlank()) {
            return rawText.trim()
        }

        val hints = promptHints.split(",")
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }

        if (hints.isEmpty()) {
            return rawText.trim()
        }

        var result = rawText.trim()

        // 1. Strip leading recitation of hints (e.g., "Word1, Word2, Word3. actual speech...")
        result = stripLeadingRecitation(result, hints)

        // 2. Strip trailing hint echo with strict looping rule (contracts/01 §10)
        result = stripTrailingHintEcho(result, hints)

        return result.trim()
    }

    /**
     * Detects if the transcript begins with a sequence of 3+ hint words in order and removes them.
     */
    fun stripLeadingRecitation(text: String, hints: List<String>): String {
        if (hints.size < 3) return text

        var cursor = text
        var matchedCount = 0

        for (hint in hints) {
            val trimmed = cursor.trimStart { it == '.' || it == ',' || it == '!' || it == '?' || it.isWhitespace() }
            if (trimmed.startsWith(hint, ignoreCase = true)) {
                val after = trimmed.substring(hint.length)
                if (after.isEmpty() || after[0].isWhitespace() || after[0] in ",.;:!?") {
                    cursor = after
                    matchedCount++
                    continue
                }
            }
            break
        }

        return if (matchedCount >= 3) {
            cursor.trimStart { it == '.' || it == ',' || it == '!' || it == '?' || it.isWhitespace() }
        } else {
            text
        }
    }

    /**
     * Detects and removes trailing hint echo loops per contracts/01 §10:
     * Only strips if the trailing run is entirely hint words AND at least one hint word appears 3+ times.
     * Preserves legitimate dictation like "Let's ask Ollama" and "Say hi to Sara".
     */
    fun stripTrailingHintEcho(text: String, hints: List<String>): String {
        if (text.isBlank() || hints.isEmpty()) return text

        val hintSet = hints.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (hintSet.isEmpty()) return text

        val wordRegex = Regex("[\\p{L}\\p{N}_]+")
        val matches = wordRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        var runStartIndex = -1
        val trailingWords = mutableListOf<String>()

        for (i in matches.indices.reversed()) {
            val match = matches[i]
            val wordLower = match.value.lowercase()
            if (wordLower in hintSet) {
                trailingWords.add(wordLower)
                runStartIndex = match.range.first
            } else {
                break
            }
        }

        if (trailingWords.isEmpty() || runStartIndex < 0) {
            return text
        }

        // Condition 3: At least one hint word appears 3 or more times in the trailing run
        val counts = mutableMapOf<String, Int>()
        for (w in trailingWords) {
            counts[w] = (counts[w] ?: 0) + 1
        }

        val hasLoopingWord = counts.values.any { it >= 3 }
        if (!hasLoopingWord) {
            return text
        }

        var contentBefore = text.substring(0, runStartIndex).trimEnd()
        while (contentBefore.isNotEmpty() && contentBefore.last() in ",;:–—") {
            contentBefore = contentBefore.dropLast(1).trimEnd()
        }

        return contentBefore
    }
}
