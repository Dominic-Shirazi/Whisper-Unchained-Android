// This file implements trigger suggestion matching and instruction parsing.
// It is called by FloatingButtonService (when typing in the typed instruction panel) and unit tests.
// Inputs: typed instruction String, list of enabled CustomTrigger objects, selection range.
// Outputs: ranked list of suggested CustomTrigger objects, chosen trigger detection, or completion text.
// Strictly ports client/prompt_panel.py and conforms to contracts/01-transcription-api.md §12 and F-54.

package com.example.whisperremote.util

// Reason for edit: Update PLACEHOLDER to "Type an instruction, or / for your skills" and add askAiLines, skillSummary, and skillWording helpers per F-62.
// Reason for creation: Port Python prompt_panel pure suggestion logic to Kotlin per contract 01 §12 and F-54.

import com.example.whisperremote.data.CustomTrigger
import java.util.regex.Pattern

object PromptPanelLogic {

    const val SUGGEST_MAX = 6
    const val SUGGEST_QUERY_MAX = 40
    const val INSTRUCTION_MAX = 4000
    const val PLACEHOLDER = "Type an instruction, or / for your skills"
    const val HINT_TEXT = "Ticked: the AI changes the highlighted text. Not ticked: its answer goes in after it."

    const val ASK_CARD_TITLE = "Ask the AI to change your text"
    const val ASK_CARD_BUTTON = "Choose your own phrase"
    const val ASK_CARD_NO_PHRASE = "You have no phrase for asking the AI yet. Add one to ask by voice."

    private val SPACE_REGEX = Regex("\\s+")
    private val WORD_REGEX = Pattern.compile("[^\\W_]+")

    private val SKILL_WORDS = listOf(
        "a built-in trigger phrase" to "one of your phrases for asking the AI",
        "Custom triggers" to "Skills",
        "custom triggers" to "skills",
        "Custom trigger" to "Skill",
        "custom trigger" to "skill",
        "Trigger phrases" to "Phrases",
        "trigger phrases" to "phrases",
        "Trigger phrase" to "Phrase",
        "trigger phrase" to "phrase",
        "Triggers" to "Skills",
        "triggers" to "skills",
        "Trigger" to "Skill",
        "trigger" to "skill"
    )

    fun askAiLines(phrase: String?): Pair<String, String> {
        val clean = (phrase ?: "").trim().replace(SPACE_REGEX, " ")
        if (clean.isEmpty()) {
            return ASK_CARD_NO_PHRASE to ""
        }
        val capitalized = clean.replaceFirstChar { it.uppercase() }
        return "Say \"$clean\", then what you want." to "\"$capitalized, make this sound more formal.\""
    }

    fun skillSummary(phrases: List<String>?): String {
        val clean = (phrases ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return ""
        val more = clean.size - 1
        return if (more > 0) {
            "say \"${clean[0]}\" (+$more more)"
        } else {
            "say \"${clean[0]}\""
        }
    }

    fun skillWording(message: String?): String {
        if (message.isNullOrEmpty()) return message ?: ""
        val quoteRegex = Regex("(\"[^\"]*\")")
        val sb = StringBuilder()
        var lastEnd = 0
        for (match in quoteRegex.findAll(message)) {
            var unquoted = message.substring(lastEnd, match.range.first)
            for ((oldWord, newWord) in SKILL_WORDS) {
                unquoted = unquoted.replace(oldWord, newWord)
            }
            sb.append(unquoted)
            sb.append(match.value)
            lastEnd = match.range.last + 1
        }
        var tail = message.substring(lastEnd)
        for ((oldWord, newWord) in SKILL_WORDS) {
            tail = tail.replace(oldWord, newWord)
        }
        sb.append(tail)
        return sb.toString()
    }

    fun normalize(s: String?): String {
        return (s ?: "").trim().lowercase().replace(SPACE_REGEX, " ")
    }

    fun words(s: String?): List<String> {
        val norm = normalize(s)
        val matcher = WORD_REGEX.matcher(norm)
        val result = mutableListOf<String>()
        while (matcher.find()) {
            result.add(matcher.group())
        }
        return result
    }

    /**
     * Finds the trigger whose phrase `text` already contains, or null.
     * Picks the longest matching phrase.
     */
    fun chosen(text: String?, triggers: List<CustomTrigger>?): CustomTrigger? {
        if (text.isNullOrBlank() || triggers.isNullOrEmpty()) return null
        val t = words(text).joinToString(" ")
        if (t.isEmpty()) return null

        var bestLen = -1
        var bestTrigger: CustomTrigger? = null

        for (trig in triggers) {
            for (phrase in trig.phrases) {
                val p = words(phrase).joinToString(" ")
                if (p.isNotEmpty()) {
                    val pattern = Pattern.compile("(?<!\\w)" + Pattern.quote(p) + "(?!\\w)")
                    if (pattern.matcher(t).find()) {
                        if (p.length > bestLen) {
                            bestLen = p.length
                            bestTrigger = trig
                        }
                    }
                }
            }
        }
        return bestTrigger
    }

    /**
     * Custom triggers to offer for what has been typed so far.
     * "/" alone lists them all (up to limit).
     * Otherwise at least two letters, matched against:
     * - start of a trigger's phrase (rank 0)
     * - start of trigger's name (rank 1)
     * - start of any word in phrase or name (rank 2)
     * Nothing once the text holds a whole phrase, is multi-line, or longer than SUGGEST_QUERY_MAX.
     */
    fun suggest(
        text: String?,
        triggers: List<CustomTrigger>?,
        limit: Int = SUGGEST_MAX
    ): List<CustomTrigger> {
        val raw = text ?: ""
        if (raw.trim().contains("\n")) return emptyList()

        val trimmedLeading = raw.trimStart()
        val slash = trimmedLeading.startsWith("/")
        val q = normalize(trimmedLeading.removePrefix("/"))
        val items = (triggers ?: emptyList()).filter { it.phrases.isNotEmpty() && it.enabled }

        if (q.isEmpty()) {
            return if (slash) items.take(limit) else emptyList()
        }

        if (q.length < 2 || q.length > SUGGEST_QUERY_MAX || chosen(q, items) != null) {
            return emptyList()
        }

        val qw = words(q).joinToString(" ")
        if (qw.isEmpty()) return emptyList()

        data class RankedItem(val rank: Int, val index: Int, val trigger: CustomTrigger)
        val ranked = mutableListOf<RankedItem>()

        for ((index, trig) in items.withIndex()) {
            val nameWords = words(trig.name).joinToString(" ")
            val phraseWordsList = trig.phrases.map { words(it).joinToString(" ") }

            val rank = when {
                phraseWordsList.any { it.startsWith(qw) } -> 0
                nameWords.startsWith(qw) -> 1
                (phraseWordsList + listOf(nameWords)).any { (" " + it).contains(" " + qw) } -> 2
                else -> -1
            }

            if (rank >= 0) {
                ranked.add(RankedItem(rank, index, trig))
            }
        }

        ranked.sortWith(compareBy({ it.rank }, { it.index }))
        return ranked.take(limit).map { it.trigger }
    }

    /**
     * What accepting a suggestion puts in the box: the first phrase plus a trailing space.
     */
    fun completion(trigger: CustomTrigger): String {
        val firstPhrase = trigger.phrases.firstOrNull()?.trim() ?: ""
        return "$firstPhrase "
    }

    fun completion(typed: String, trigger: CustomTrigger): String {
        return completion(trigger)
    }

    /**
     * Label for "Include the highlighted text (N words)".
     */
    fun includeLabel(selection: String): String {
        val count = words(selection).size
        return includeLabel(count)
    }

    fun includeLabel(count: Int): String {
        return if (count == 1) {
            "Include the highlighted text (1 word)"
        } else {
            "Include the highlighted text ($count words)"
        }
    }
}
