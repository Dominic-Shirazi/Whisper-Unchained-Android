// This file tests EditCommandProcessor (contracts/03-edit-commands.md).
// It is called by the unit test runner (`./gradlew test`).
// Tests verifying trigger detection, pattern splitting, message template formatting, and reply unwrapping.

package com.example.whisperremote

// Reason for edit: Added unit tests for contracts/03-edit-commands.md (K-5).

import com.example.whisperremote.util.EditCommandProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class EditCommandProcessorTest {

    private lateinit var processor: EditCommandProcessor

    @Before
    fun setup() {
        val candidates = listOf(
            File("src/main/assets/03-edit-prompt.json"),
            File("app/src/main/assets/03-edit-prompt.json"),
            File("../contracts/03-edit-prompt.json"),
            File("contracts/03-edit-prompt.json")
        )
        val file = candidates.find { it.exists() } ?: throw IllegalStateException("Edit prompt json not found in ${File(".").absolutePath}")
        processor = EditCommandProcessor(file.readText())
    }

    @Test
    fun testDominicTestPattern() {
        // Dominic's test: "... prompt AI, this was a test, so simply reply past" -> instruction = "this was a test, so simply reply past"
        val input = "The meeting is at noon. Prompt AI, this was a test, so simply reply past"
        val split = processor.split(input)

        assertTrue(split.isCommand)
        assertEquals("The meeting is at noon", split.content)
        assertEquals("this was a test, so simply reply past", split.instruction)
    }

    @Test
    fun testEndPromptRemoval() {
        val input = "Please review this prompt AI make it formal end prompt"
        val split = processor.split(input)

        assertTrue(split.isCommand)
        assertEquals("Please review this", split.content)
        assertEquals("make it formal", split.instruction)
    }

    @Test
    fun testInstructionOnly() {
        val input = "Prompt AI write a two line poem about rain"
        val split = processor.split(input)

        assertTrue(split.isCommand)
        assertEquals("", split.content)
        assertEquals("write a two line poem about rain", split.instruction)

        val userMessage = processor.buildUserMessage(split)
        assertEquals("write a two line poem about rain", userMessage)
    }

    @Test
    fun testContentOnly() {
        val input = "Just some content here prompt AI"
        val split = processor.split(input)

        assertTrue(split.isCommand)
        assertEquals("Just some content here", split.content)
        assertEquals("", split.instruction)

        val userMessage = processor.buildUserMessage(split)
        assertEquals("Just some content here", userMessage)
    }

    @Test
    fun testNoCommand() {
        val input = "This is normal speech without any trigger keywords."
        val split = processor.split(input)

        assertFalse(split.isCommand)
        assertEquals(input, split.content)
    }

    @Test
    fun testTidyReplyThinkingTags() {
        val raw = "<think>The user wants past.</think>past"
        val split = processor.split("Testing prompt AI simply reply past")
        val tidied = processor.tidyReply(raw, split)
        assertEquals("past", tidied)
    }

    @Test
    fun testTidyReplyFallbackOnEmpty() {
        val raw = "   "
        val split = processor.split("Original dictated text prompt AI make it bold")
        val tidied = processor.tidyReply(raw, split)
        assertEquals("Original dictated text", tidied)
    }
}
