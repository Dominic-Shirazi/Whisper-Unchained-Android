// This file tests TranscriptCleaner (contracts/02-cleanup.md Version 2).
// It is called by the unit test runner (`./gradlew test`).
// Tests verifying prompt assembly (including tone) and reply tidying.

package com.example.whisperremote

// Reason for edit: Updated unit tests for Version 2 (tone support and forced neutral when rewrite == keep).

import com.example.whisperremote.util.CleanupOptions
import com.example.whisperremote.util.TranscriptCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TranscriptCleanerTest {

    private lateinit var cleaner: TranscriptCleaner

    @Before
    fun setup() {
        val candidates = listOf(
            File("src/main/assets/02-cleanup-prompt.json"),
            File("app/src/main/assets/02-cleanup-prompt.json"),
            File("../contracts/02-cleanup-prompt.json"),
            File("contracts/02-cleanup-prompt.json")
        )
        val file = candidates.find { it.exists() } ?: throw IllegalStateException("Prompt json not found in ${File(".").absolutePath}")
        cleaner = TranscriptCleaner(file.readText())
    }

    @Test
    fun testBuildSystemPromptDefault() {
        val options = CleanupOptions(punctuation = "standard", rewrite = "keep", tone = "neutral", shorten = "off", emoji = "off")
        val prompt = cleaner.buildSystemPrompt(options, listOf("Lesley", "Ollama"))

        assertTrue(prompt.contains("Spell these names and terms exactly as written here: Lesley, Ollama."))
        assertTrue(prompt.contains("- Use normal capitalization and punctuation."))
        assertTrue(prompt.contains("- Use the speaker's own words in the speaker's order."))
        // Version 2: Tone line inserted after rewrite
        assertTrue(prompt.contains("- Keep the speaker's own tone."))
        assertFalse(prompt.contains("When shortening, remove material instead of rewording what remains."))
        assertTrue(prompt.endsWith("Reply with the cleaned text only: no tags, no quotation marks around it, no labels and no notes."))
    }

    @Test
    fun testBuildSystemPromptToneWhenRewriteKeepForcedNeutral() {
        // When rewrite is keep, tone should be forced to neutral even if another tone is passed
        val options = CleanupOptions(punctuation = "standard", rewrite = "keep", tone = "professional", shorten = "off", emoji = "off")
        val prompt = cleaner.buildSystemPrompt(options)

        assertTrue(prompt.contains("- Keep the speaker's own tone."))
        assertFalse(prompt.contains("- Use a clear, professional tone suitable for work."))
    }

    @Test
    fun testBuildSystemPromptToneWhenRewriteFix() {
        val options = CleanupOptions(punctuation = "standard", rewrite = "fix", tone = "professional", shorten = "off", emoji = "off")
        val prompt = cleaner.buildSystemPrompt(options)

        assertTrue(prompt.contains("- Stay close to the speaker's wording, but fix grammar and clumsy phrasing."))
        assertTrue(prompt.contains("- Use a clear, professional tone suitable for work."))
    }

    @Test
    fun testBuildSystemPromptWithShortening() {
        val options = CleanupOptions(punctuation = "light", rewrite = "keep", shorten = "light", emoji = "on")
        val prompt = cleaner.buildSystemPrompt(options)

        assertTrue(prompt.contains("- Cut repetition and rambling, but keep every separate point."))
        assertTrue(prompt.contains("- When shortening, remove material instead of rewording what remains."))
        assertTrue(prompt.contains("- You may add one fitting emoji to a casual message."))
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
