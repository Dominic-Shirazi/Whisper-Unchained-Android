// This file implements post-transcription text processing rules for on-device and fallback dictation.
// It is called by ApiClient, FloatingButtonService, and on-device transcription pipelines.
// Inputs: Transcribed raw text, optional custom filler sound list, name corrections map ("heard as" -> "write as").
// Outputs: Cleaned text with filler sounds removed and name corrections applied according to canonical server logic.
// Strictly conforms to contracts/05-on-device.md §2.4, contracts/05-text-rules-tests.json, and api/fillers.py.
// Reason for creation: Port server name corrections and filler sound removal to Kotlin for on-device v1.3 per F-28.
// Reason for edit: Add formatCorrectionsText for name corrections editor per F-43.

package com.example.whisperremote.util

import java.util.regex.Matcher
import java.util.regex.Pattern

object TextRules {

    val DEFAULT_FILLERS: List<String> = listOf("um", "umm", "uh", "uhh", "hmm")

    private val SPACE_COLLAPSE = Pattern.compile("\\s{2,}")
    private val REPEATED_DOT = Pattern.compile("([.!?])\\s+\\.(?!\\.)")
    private val SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.!?;:])")
    private val COMMA_BEFORE_PUNCT = Pattern.compile(",\\s*([.!?])")
    private val LEADING_SPACES_COMMAS = Pattern.compile("^[\\s,]+")
    private val SENTENCE_RECAPITALIZE = Pattern.compile("(^|(?<!\\.)[.!?]\\s+)([a-z])")

    /**
     * Replaces whole-word occurrences of name corrections ("heard as" -> "write as"), case-insensitively,
     * matching Python's apply_name_corrections exactly per contracts/05 §2.4.
     */
    fun applyNameCorrections(text: String, corrections: Map<String, String>): String {
        if (text.isEmpty() || corrections.isEmpty()) return text
        var result = text
        for ((heard, writeAs) in corrections) {
            if (heard.isEmpty()) continue
            val regex = Regex("(?<!\\w)${Regex.escape(heard)}(?!\\w)", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { writeAs }
        }
        return result
    }

    /**
     * Removes filler sounds (um, uh, etc.) according to the server's remove_fillers rule (api/fillers.py).
     * Deliberately narrow: only standalone tokens, never acronyms like "UM" or words like "uh-oh".
     *
     * @return Pair of (cleanedText, removedFillerWords)
     */
    fun removeFillers(text: String, words: List<String> = DEFAULT_FILLERS): Pair<String, List<String>> {
        if (text.isEmpty() || words.isEmpty()) {
            return Pair(text, emptyList())
        }

        val alts = words.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) }
        val pattern = Pattern.compile(
            "(?<lead>,\\s*)?(?<![\\w'\\-])(?<word>$alts)(?:\\.\\.\\.|…)?(?<trail>\\s*,)?(?![\\w'\\-])",
            Pattern.CASE_INSENSITIVE
        )

        val matcher = pattern.matcher(text)
        val removed = mutableListOf<String>()
        val sb = StringBuffer()

        while (matcher.find()) {
            val word = matcher.group("word") ?: ""
            if (word.length > 1 && word.all { it.isUpperCase() }) {
                // "UM", "UH": likely an acronym, leave intact
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0) ?: ""))
                continue
            }
            removed.add(word)

            val lead = matcher.group("lead")
            val trail = matcher.group("trail")

            val replacement = if (!lead.isNullOrEmpty() && !trail.isNullOrEmpty()) {
                ""
            } else if (!lead.isNullOrEmpty()) {
                ","
            } else {
                ""
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)

        if (removed.isEmpty()) {
            return Pair(text, emptyList())
        }

        var out = sb.toString()
        out = SPACE_COLLAPSE.matcher(out).replaceAll(" ")
        out = REPEATED_DOT.matcher(out).replaceAll("$1")
        out = SPACE_BEFORE_PUNCT.matcher(out).replaceAll("$1")
        out = COMMA_BEFORE_PUNCT.matcher(out).replaceAll("$1")
        out = LEADING_SPACES_COMMAS.matcher(out).replaceAll("").trim()

        // Re-capitalize a sentence whose first word was the filler: "Um, so" -> "So".
        // (Not after an ellipsis: "Wait... what?" is one sentence.)
        val recapMatcher = SENTENCE_RECAPITALIZE.matcher(out)
        val recapSb = StringBuffer()
        while (recapMatcher.find()) {
            val prefix = recapMatcher.group(1) ?: ""
            val letter = recapMatcher.group(2) ?: ""
            recapMatcher.appendReplacement(recapSb, Matcher.quoteReplacement(prefix + letter.uppercase()))
        }
        recapMatcher.appendTail(recapSb)
        out = recapSb.toString()

        return Pair(out, removed)
    }

    // Reason for edit: Add parseCorrectionsText helper for parsing multi-line Heard:WriteAs pairs per contract 05 §2.4
    /**
     * Parses multi-line name corrections (e.g. "Heard as: Write as", "Leslie -> Lesley", "C plus plus = C++").
     * Ignores comments (lines starting with #) and empty lines.
     */
    fun parseCorrectionsText(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val delimiter = when {
                trimmed.contains("->") -> "->"
                trimmed.contains(":") -> ":"
                trimmed.contains("=") -> "="
                else -> null
            }
            if (delimiter != null) {
                val parts = trimmed.split(delimiter, limit = 2)
                if (parts.size == 2) {
                    val heard = parts[0].trim()
                    val writeAs = parts[1].trim()
                    if (heard.isNotEmpty() && writeAs.isNotEmpty()) {
                        map[heard] = writeAs
                    }
                }
            }
        }
        return map
    }

    // Reason for edit: Add formatCorrectionsText for formatting name corrections map into lines per F-43
    /**
     * Formats a name corrections map into multi-line "Heard as: Write as" format.
     */
    fun formatCorrectionsText(corrections: Map<String, String>): String {
        if (corrections.isEmpty()) return ""
        return corrections.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
}
