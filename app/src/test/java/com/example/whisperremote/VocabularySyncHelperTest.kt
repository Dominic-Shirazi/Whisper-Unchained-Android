// This file contains unit tests for VocabularySyncHelper.
// It is called by the Gradle test runner during ./gradlew test.
// Inputs: Sample vocabulary strings, mock server lists, and name correction entries.
// Outputs: Verifies parsing, case-insensitive deduplication, new word detection, and length limits.

package com.example.whisperremote

// Reason for edit: Added comprehensive unit tests for two-way vocabulary sync and deduplication.

import com.example.whisperremote.util.VocabularySyncHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularySyncHelperTest {

    @Test
    fun testParseWordsSplitsAndTrims() {
        val input = "Dominic, Tailscale\nKubernetes; Whisper Unchained ,  ollama  "
        val words = VocabularySyncHelper.parseWords(input)
        assertEquals(listOf("Dominic", "Tailscale", "Kubernetes", "Whisper Unchained", "ollama"), words)
    }

    @Test
    fun testParseWordsDeduplicatesCaseInsensitive() {
        val input = "Ollama, ollama, OLLAMA, Qwen, qwen"
        val words = VocabularySyncHelper.parseWords(input)
        assertEquals(listOf("Ollama", "Qwen"), words)
    }

    @Test
    fun testFindNewWordsDetectsOnlyUnseenEntries() {
        val localWords = listOf("Dominic", "Tailscale", "NewClient", "Sara", "Supercalifragilistic")
        val serverVocab = listOf("dominic", "tailscale")
        val fromNameCorrections = listOf("Sara")

        val newWords = VocabularySyncHelper.findNewWords(localWords, serverVocab, fromNameCorrections)
        assertEquals(listOf("NewClient", "Supercalifragilistic"), newWords)
    }

    @Test
    fun testFindNewWordsEnforcesMaxLengthAndLimit() {
        val tooLong = "A".repeat(61)
        val valid = "ValidWord"
        val newWords = VocabularySyncHelper.findNewWords(listOf(tooLong, valid), emptyList())
        assertEquals(listOf(valid), newWords)
    }

    @Test
    fun testMergeAndDeduplicatePreservesServerFirst() {
        val server = listOf("Qwen", "Ollama")
        val local = listOf("ollama", "NewWord", "QWEN")
        val merged = VocabularySyncHelper.mergeAndDeduplicate(server, local)
        assertEquals(listOf("Qwen", "Ollama", "NewWord"), merged)
    }

    @Test
    fun testFormatForDisplay() {
        val words = listOf("Qwen", "Ollama", "Tailscale")
        assertEquals("Qwen, Ollama, Tailscale", VocabularySyncHelper.formatForDisplay(words))
    }
}
