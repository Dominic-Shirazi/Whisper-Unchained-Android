// This file tests TranscriptSanitizer.
// It is called by the unit test runner (`./gradlew test`).
// Tests verifying that prompt hint words are NOT stripped when spoken legitimately (e.g. "Let's ask Ollama"),
// but echo loops (repeating 3+ times at end) and leading recitations (3+ hints in order) are cleanly stripped.

package com.example.whisperremote

// Reason for edit: Updated unit tests per contracts/01 §10 (K-6): single trailing hints are preserved; only loops with 3+ repetitions of a hint are stripped.

import com.example.whisperremote.util.TranscriptSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSanitizerTest {

    @Test
    fun testNoTrailingHints() {
        val input = "Hello, this is a normal test."
        val hints = "Lesley, Ollama, WhisperRemote"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("Hello, this is a normal test.", output)
    }

    @Test
    fun testPreservesSingleHintAtEndOllama() {
        // Dominic's rule: "Let's ask Ollama" must not be trimmed
        val input = "Let's ask Ollama"
        val hints = "Lesley, Ollama, Sara"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("Let's ask Ollama", output)
    }

    @Test
    fun testPreservesSingleHintAtEndSara() {
        val input = "Say hi to Sara"
        val hints = "Lesley, Ollama, Sara"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("Say hi to Sara", output)
    }

    @Test
    fun testEchoLoopDominicCaseStripped() {
        // Dominic's exact dictation pattern: hint loop repeating 3+ times
        val input = "At the top of the page. Ollama, Lesley, Ame, Sara, Lesley, Ame, Sara, Lesley."
        val hints = "Ollama, Lesley, Ame, Sara"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("At the top of the page.", output)
    }

    @Test
    fun testTrailingRepetitiveHintLoop() {
        val input = "Checking server status. Ollama. Ollama, Ollama."
        val hints = "Lesley, Ollama"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("Checking server status.", output)
    }

    @Test
    fun testLeadingRecitationStripped() {
        val input = "Lesley, Ollama, WhisperRemote. Here is the actual message I am speaking."
        val hints = "Lesley, Ollama, WhisperRemote, Kubernetes"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("Here is the actual message I am speaking.", output)
    }

    @Test
    fun testEntireTextIsRecitation() {
        val input = "Lesley, Ollama, WhisperRemote."
        val hints = "Lesley, Ollama, WhisperRemote"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("", output)
    }

    @Test
    fun testLegitimateInternalHintPreserved() {
        val input = "I told Lesley to check the Ollama server."
        val hints = "Lesley, Ollama"
        val output = TranscriptSanitizer.sanitize(input, hints)
        assertEquals("I told Lesley to check the Ollama server.", output)
    }

    @Test
    fun testClampHintsUnderMaxBudget() {
        val hints = "Lesley, Ollama, Kubernetes"
        val clamped = TranscriptSanitizer.clampHints(hints)
        assertEquals(hints, clamped)
    }

    @Test
    fun testClampHintsOverMaxBudget() {
        val longHints = (1..60).joinToString(", ") { "WordNumber$it" }
        assertTrue(longHints.length > TranscriptSanitizer.MAX_HINT_CHARS)

        val clamped = TranscriptSanitizer.clampHints(longHints)
        assertTrue(clamped.length <= TranscriptSanitizer.MAX_HINT_CHARS)
        assertTrue(!clamped.endsWith(","))
    }
}
