// This file tests TranscriptCleaner (contracts/02-cleanup.md Version 2).
// It is called by the unit test runner (`./gradlew test`).
// Tests verifying prompt assembly (including tone) and reply tidying.

package com.example.whisperremote

// Reason for edit: Dynamically read expected prompt strings from JSON so future text changes don't break tests (F-51).

import com.example.whisperremote.util.CleanupOptions
import com.example.whisperremote.util.TranscriptCleaner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TranscriptCleanerTest {

    private lateinit var cleaner: TranscriptCleaner
    private lateinit var jsonObject: JSONObject

    @Before
    fun setup() {
        val candidates = listOf(
            File("src/main/assets/02-cleanup-prompt.json"),
            File("app/src/main/assets/02-cleanup-prompt.json"),
            File("../contracts/02-cleanup-prompt.json"),
            File("contracts/02-cleanup-prompt.json")
        )
        val file = candidates.find { it.exists() } ?: throw IllegalStateException("Prompt json not found in ${File(".").absolutePath}")
        val jsonText = file.readText()
        cleaner = TranscriptCleaner(jsonText)
        jsonObject = JSONObject(jsonText)
    }

    private fun getStyleString(category: String, key: String): String {
        return jsonObject.getJSONObject("style").getJSONObject(category).getString(key)
    }

    @Test
    fun testBuildSystemPromptDefault() {
        val options = CleanupOptions(punctuation = "standard", rewrite = "keep", tone = "neutral", shorten = "off", emoji = "off")
        val prompt = cleaner.buildSystemPrompt(options, listOf("Lesley", "Ollama"))

        assertTrue(prompt.contains("Spell these names and terms exactly as written here: Lesley, Ollama."))
        assertTrue(prompt.contains(getStyleString("punctuation", "standard")))
        assertTrue(prompt.contains(getStyleString("rewrite", "keep")))
        // Version 2: Tone line inserted after rewrite
        assertTrue(prompt.contains(getStyleString("tone", "neutral")))
        assertFalse(prompt.contains(jsonObject.getString("keep_while_shortening")))
        assertTrue(prompt.endsWith(jsonObject.getString("final")))
    }

    @Test
    fun testBuildSystemPromptToneWhenRewriteKeepForcedNeutral() {
        // When rewrite is keep, tone should be forced to neutral even if another tone is passed
        val options = CleanupOptions(punctuation = "standard", rewrite = "keep", tone = "professional", shorten = "off", emoji = "off")
        val prompt = cleaner.buildSystemPrompt(options)

        assertTrue(prompt.contains(getStyleString("tone", "neutral")))
        assertFalse(prompt.contains(getStyleString("tone", "professional")))
    }

    @Test
    fun testBuildSystemPromptToneWhenRewriteFix() {
        val options = CleanupOptions(punctuation = "standard", rewrite = "fix", tone = "professional", shorten = "off", emoji = "off")
        val prompt = cleaner.buildSystemPrompt(options)

        assertTrue(prompt.contains(getStyleString("rewrite", "fix")))
        assertTrue(prompt.contains(getStyleString("tone", "professional")))
    }

    @Test
    fun testBuildSystemPromptWithShortening() {
        val options = CleanupOptions(punctuation = "light", rewrite = "keep", shorten = "light", emoji = "on")
        val prompt = cleaner.buildSystemPrompt(options)

        assertTrue(prompt.contains(getStyleString("shorten", "light")))
        assertTrue(prompt.contains(jsonObject.getString("keep_while_shortening")))
        assertTrue(prompt.contains(getStyleString("emoji", "on")))
    }

    @Test
    fun testTidyReplyThinkingTags() {
        val raw = "<think>Let me reason about this sentence.</think>This is the actual cleaned output."
        val tidied = cleaner.tidyReply(raw, "raw input")
        assertEquals("This is the actual cleaned output.", tidied)
    }

    @Test
    fun testTidyReplyDictationTags() {
        val raw = "<dictation>\nHello world, how are you today?\n</dictation>"
        val tidied = cleaner.tidyReply(raw, "raw input")
        assertEquals("Hello world, how are you today?", tidied)
    }

    @Test
    fun testTidyReplyQuotes() {
        val raw = "\"This is quoted cleaned text.\""
        val tidied = cleaner.tidyReply(raw, "raw input")
        assertEquals("This is quoted cleaned text.", tidied)
    }

    @Test
    fun testTidyReplyPreamble() {
        val raw = "Here is the cleaned text:\n\nWe met at noon."
        val tidied = cleaner.tidyReply(raw, "raw input")
        assertEquals("We met at noon.", tidied)
    }

    @Test
    fun testTidyReplyFallbackOnEmpty() {
        val raw = "   \n\n  "
        val original = "Fall back to this original transcription"
        val tidied = cleaner.tidyReply(raw, original)
        assertEquals(original, tidied)
    }
}
