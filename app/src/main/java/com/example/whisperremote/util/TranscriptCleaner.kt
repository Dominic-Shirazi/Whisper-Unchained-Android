// This file builds and tidies LLM dictation cleanup requests.
// It is called by ApiClient when optional dictation cleanup is enabled for cloud presets, and by test runners.
// Inputs: Style options (punctuation, rewrite, tone, shorten, emoji), vocabulary words, raw transcript, prompt configuration JSON.
// Outputs: System prompt string, user message string, and tidied post-processed cleaned text.
// It complies strictly with contracts/02-cleanup.md (Version 2) and contracts/02-cleanup-prompt.json.

package com.example.whisperremote.util

// Reason for edit: Updated to Version 2 per contract K-5 (added tone style key: neutral, professional, casual, friendly; forced to neutral when rewrite == keep).

import org.json.JSONObject

data class CleanupOptions(
    val punctuation: String = "standard", // standard | light | minimal
    val rewrite: String = "keep",         // keep | fix | polish
    val tone: String = "neutral",         // neutral | professional | casual | friendly
    val shorten: String = "off",          // off | light | strong
    val emoji: String = "off"             // off | on
)

class TranscriptCleaner(promptJsonString: String) {

    private val json = JSONObject(promptJsonString)
    private val rules: List<String>
    private val vocabularyRule: String
    private val styleHeader: String
    private val styleObj: JSONObject
    private val keepWhileShortening: String
    private val finalRule: String
    private val userTemplate: String

    init {
        val rulesArr = json.getJSONArray("rules")
        rules = (0 until rulesArr.length()).map { rulesArr.getString(it) }
        vocabularyRule = json.getString("vocabulary_rule")
        styleHeader = json.getString("style_header")
        styleObj = json.getJSONObject("style")
        keepWhileShortening = json.getString("keep_while_shortening")
        finalRule = json.getString("final")
        userTemplate = json.getString("user_template")
    }

    /**
     * Builds the exact system prompt per contracts/02-cleanup.md §2 (Version 2).
     */
    fun buildSystemPrompt(options: CleanupOptions, vocabulary: List<String> = emptyList()): String {
        val parts = mutableListOf<String>()

        // 1. Every string in rules, in order
        parts.addAll(rules)

        // 2. Vocabulary rule if vocabulary is non-empty
        val cleanVocab = vocabulary.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanVocab.isNotEmpty()) {
            val termsStr = cleanVocab.joinToString(", ")
            parts.add(vocabularyRule.replace("{terms}", termsStr))
        }

        // 3. Style rules in exact order: punctuation, rewrite, tone, shorten, emoji
        val styleLines = mutableListOf<String>()
        styleLines.add(styleHeader)

        val punctRule = styleObj.getJSONObject("punctuation").optString(options.punctuation, "")
        if (punctRule.isNotEmpty()) styleLines.add("- $punctRule")

        val rewriteRule = styleObj.getJSONObject("rewrite").optString(options.rewrite, "")
        if (rewriteRule.isNotEmpty()) styleLines.add("- $rewriteRule")

        // Tone rule: when rewrite == keep, tone is forced to neutral per contract 02 v2
        val effectiveTone = if (options.rewrite == "keep") "neutral" else options.tone
        val toneObj = styleObj.optJSONObject("tone")
        val toneRule = toneObj?.optString(effectiveTone, "") ?: ""
        if (toneRule.isNotEmpty()) styleLines.add("- $toneRule")

        val shortenRule = styleObj.getJSONObject("shorten").optString(options.shorten, "")
        if (shortenRule.isNotEmpty()) styleLines.add("- $shortenRule")

        val emojiRule = styleObj.getJSONObject("emoji").optString(options.emoji, "")
        if (emojiRule.isNotEmpty()) styleLines.add("- $emojiRule")

        if (options.rewrite == "keep" && options.shorten != "off") {
            styleLines.add("- $keepWhileShortening")
        }

        parts.add(styleLines.joinToString("\n"))

        // 4. Final rule
        parts.add(finalRule)

        return parts.joinToString("\n")
    }

    /**
     * Builds the user message per contracts/02-cleanup.md §2.
     */
    fun buildUserMessage(transcript: String): String {
        return userTemplate.replace("{text}", transcript)
    }

    /**
     * Tidies the LLM reply per contracts/02-cleanup.md §3.
     */
    fun tidyReply(rawReply: String, originalTranscript: String): String {
        if (rawReply.isBlank()) return originalTranscript

        var text = rawReply.trim()

        // 1. Remove thinking / reasoning tags (case-insensitive). If an opening tag remains with no close, drop it and everything after it.
        text = stripThinkingTags(text).trim()

        // 2. If the whole reply is one code fence (```lang\n...\n```), keep only its inside
        text = stripSingleCodeFence(text).trim()

        // 3. If the whole reply is wrapped in <dictation>, <text> or <output> tags, keep only inside
        text = stripWrappingTags(text).trim()

        // 4. Remove one leading label like "Here is the cleaned text:" / "Here's your dictation:" unless transcript itself starts with it
        text = stripLeadingLabel(text, originalTranscript).trim()

        // 5. If whole reply wrapped in one pair of quotes with no other quote of that kind inside, and transcript didn't start with quote, remove pair
        text = stripOuterQuotes(text, originalTranscript).trim()

        return if (text.isBlank()) originalTranscript else text
    }

    private fun stripThinkingTags(input: String): String {
        var s = input
        val tags = listOf("think", "thinking", "reasoning")
        for (tag in tags) {
            // Drop matched pair
            val regex = Regex("(?is)<$tag>.*?</$tag>")
            s = s.replace(regex, "")
            // Drop unclosed opening tag and everything after
            val openRegex = Regex("(?is)<$tag>.*")
            s = s.replace(openRegex, "")
        }
        return s
    }

    private fun stripSingleCodeFence(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length >= 6) {
            val inner = trimmed.substring(3, trimmed.length - 3).trim()
            val firstLineBreak = inner.indexOf('\n')
            return if (firstLineBreak != -1 && inner.substring(0, firstLineBreak).all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                inner.substring(firstLineBreak + 1).trim()
            } else {
                inner
            }
        }
        return input
    }

    private fun stripWrappingTags(input: String): String {
        val trimmed = input.trim()
        val tags = listOf("dictation", "text", "output")
        for (tag in tags) {
            val open = "<$tag>"
            val close = "</$tag>"
            if (trimmed.startsWith(open, ignoreCase = true) && trimmed.endsWith(close, ignoreCase = true)) {
                return trimmed.substring(open.length, trimmed.length - close.length).trim()
            }
        }
        return input
    }

    private fun stripLeadingLabel(input: String, original: String): String {
        val labelRegex = Regex("(?i)^(Here\\s+is\\s+the\\s+cleaned\\s+text|Here's\\s+your\\s+dictation|Cleaned-up\\s+text|Cleaned\\s+text|Output):\\s*")
        val match = labelRegex.find(input)
        if (match != null) {
            val origMatch = labelRegex.find(original)
            if (origMatch == null) {
                return input.substring(match.range.last + 1).trim()
            }
        }
        return input
    }

    private fun stripOuterQuotes(input: String, original: String): String {
        val pairs = listOf(Pair('"', '"'), Pair('“', '”'), Pair('\'', '\''))
        for ((qOpen, qClose) in pairs) {
            if (input.startsWith(qOpen) && input.endsWith(qClose) && input.length >= 2) {
                val inner = input.substring(1, input.length - 1)
                // Ensure no other quote of that kind exists inside
                if (!inner.contains(qOpen) && !inner.contains(qClose) && !original.startsWith(qOpen)) {
                    return inner.trim()
                }
            }
        }
        return input
    }
}
