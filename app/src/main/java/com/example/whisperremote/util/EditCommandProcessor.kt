// This file processes "prompt AI" voice edit commands.
// It is called by ApiClient when transcribing with cloud presets.
// Inputs: raw transcript, prompt configuration JSON (03-edit-prompt.json).
// Outputs: EditCommandSplit (detecting trigger phrases, extracting content & instruction), user prompt message, and tidied response.
// It complies strictly with contracts/03-edit-commands.md.

package com.example.whisperremote.util

// Reason for edit: Implemented voice edit command processor per contracts/03-edit-commands.md and 03-edit-prompt.json (K-5).

import org.json.JSONObject

data class EditCommandSplit(
    val isCommand: Boolean,
    val content: String,
    val instruction: String,
    val original: String
)

class EditCommandProcessor(promptJsonString: String) {

    private val json = JSONObject(promptJsonString)
    private val triggerPatterns: List<String>
    private val endPatterns: List<String>
    private val stripChars: String
    val systemPrompt: String
    private val userTemplateBoth: String
    private val userTemplateInstructionOnly: String
    private val userTemplateContentOnly: String

    init {
        val triggersArr = json.getJSONArray("trigger_patterns")
        // Ignore any pattern containing the letters 'end' (contracts/03-edit-commands.md §1)
        triggerPatterns = (0 until triggersArr.length())
            .map { triggersArr.getString(it) }
            .filter { !it.contains("end", ignoreCase = true) }

        val endArr = json.getJSONArray("end_patterns")
        endPatterns = (0 until endArr.length()).map { endArr.getString(it) }

        stripChars = json.optString("strip_chars", " \t\n,.;:!?-")
        systemPrompt = json.getString("system_prompt")
        userTemplateBoth = json.getString("user_template_both")
        userTemplateInstructionOnly = json.getString("user_template_instruction_only")
        userTemplateContentOnly = json.getString("user_template_content_only")
    }

    /**
     * Splits a raw transcript into content and instruction based on trigger patterns.
     * Complies with contracts/03-edit-commands.md §1.
     */
    fun split(transcript: String): EditCommandSplit {
        if (transcript.isBlank() || triggerPatterns.isEmpty()) {
            return EditCommandSplit(false, transcript.trim(), "", transcript)
        }

        // Combined regex: \b(pattern1|pattern2)\b, case-insensitive. Use first match.
        val combinedPattern = "\\b(" + triggerPatterns.joinToString("|") + ")\\b"
        val regex = Regex(combinedPattern, RegexOption.IGNORE_CASE)
        val match = regex.find(transcript) ?: return EditCommandSplit(false, transcript.trim(), "", transcript)

        val rawContent = transcript.substring(0, match.range.first)
        var rawInstruction = transcript.substring(match.range.last + 1)

        // Replace every end_patterns match with a space (removes a spoken "end prompt")
        for (endPat in endPatterns) {
            val endRegex = Regex(endPat, RegexOption.IGNORE_CASE)
            rawInstruction = endRegex.replace(rawInstruction, " ")
        }

        val content = trimStripChars(rawContent)
        val instruction = trimStripChars(rawInstruction)

        return EditCommandSplit(true, content, instruction, transcript)
    }

    /**
     * Trims characters defined in stripChars from both ends of the string.
     */
    private fun trimStripChars(text: String): String {
        var start = 0
        var end = text.length

        while (start < end && stripChars.indexOf(text[start]) >= 0) {
            start++
        }
        while (end > start && stripChars.indexOf(text[end - 1]) >= 0) {
            end--
        }
        return text.substring(start, end).trim()
    }

    /**
     * Builds the exact user prompt per contracts/03-edit-commands.md §2.
     */
    fun buildUserMessage(split: EditCommandSplit): String {
        return when {
            split.content.isNotEmpty() && split.instruction.isNotEmpty() ->
                userTemplateBoth.replace("{instruction}", split.instruction).replace("{content}", split.content)
            split.content.isEmpty() && split.instruction.isNotEmpty() ->
                userTemplateInstructionOnly.replace("{instruction}", split.instruction)
            split.content.isNotEmpty() && split.instruction.isEmpty() ->
                userTemplateContentOnly.replace("{content}", split.content)
            else -> ""
        }
    }

    /**
     * Tidies the LLM reply per contracts/03-edit-commands.md §3.
     */
    fun tidyReply(rawReply: String, split: EditCommandSplit): String {
        if (rawReply.isBlank()) {
            return split.content.ifEmpty { split.original }
        }

        var text = rawReply.trim()

        // If it contains </think>: keep only what follows the last </think>
        val lastCloseThink = text.lastIndexOf("</think>", ignoreCase = true)
        if (lastCloseThink >= 0) {
            text = text.substring(lastCloseThink + "</think>".length)
        } else {
            // Else if it contains <think>: keep only what precedes the first <think>
            val firstOpenThink = text.indexOf("<think>", ignoreCase = true)
            if (firstOpenThink >= 0) {
                text = text.substring(0, firstOpenThink)
            }
        }

        text = text.trim()
        return if (text.isNotEmpty()) text else split.content.ifEmpty { split.original }
    }
}
