// This file tests PromptPanelLogic (ported from Faster_Whisper_API/tests/test_prompt_panel.py).
// It is called by the unit test runner (`./gradlew test`).
// Tests verifying trigger suggestions, matching, completion, and chosen trigger detection.

package com.example.whisperremote

// Reason for edit: Add unit tests for F-62 skills wording, askAiLines, skillSummary, and skillWording.
// Reason for creation: Unit test suite for PromptPanelLogic per contract 01 §12 and F-54.

import com.example.whisperremote.data.CustomTrigger
import com.example.whisperremote.util.PromptPanelLogic
import org.junit.Assert.*
import org.junit.Test

class PromptPanelLogicTest {

    private val ticket = CustomTrigger(
        id = "ticket",
        name = "Bug ticket",
        phrases = listOf("prompt ticket", "ticket this"),
        prompt = "Rewrite the text as a bug ticket.",
        enabled = true
    )

    private val shorter = CustomTrigger(
        id = "shorter",
        name = "Shorter",
        phrases = listOf("prompt shorter"),
        prompt = "Make the text shorter.",
        enabled = true
    )

    private val standup = CustomTrigger(
        id = "standup",
        name = "Stand-up update",
        phrases = listOf("prompt stand up"),
        prompt = "Rewrite as a stand-up update.",
        enabled = true
    )

    private val allTriggers = listOf(ticket, shorter, standup)

    private fun names(found: List<CustomTrigger>): List<String> = found.map { it.name }

    @Test
    fun testTypingStartOfPhraseListsMatchingTriggers() {
        assertEquals(listOf("Bug ticket", "Shorter", "Stand-up update"), names(PromptPanelLogic.suggest("pro", allTriggers)))
        assertEquals(listOf("Shorter", "Stand-up update"), names(PromptPanelLogic.suggest("prompt s", allTriggers)))
        assertEquals(listOf("Shorter"), names(PromptPanelLogic.suggest("Prompt   Sh", allTriggers)))
    }

    @Test
    fun testNameOrAnyWordInItMatchesToo() {
        assertEquals(listOf("Bug ticket"), names(PromptPanelLogic.suggest("bug", allTriggers)))
        assertEquals(listOf("Bug ticket"), names(PromptPanelLogic.suggest("tick", allTriggers))) // phrase start comes first
        assertEquals(listOf("Stand-up update"), names(PromptPanelLogic.suggest("up", allTriggers))) // word inside
        assertEquals(listOf("Stand-up update"), names(PromptPanelLogic.suggest("stand-up", allTriggers)))
    }

    @Test
    fun testSlashListsEverythingAndCanBeNarrowed() {
        assertEquals(listOf("Bug ticket", "Shorter", "Stand-up update"), names(PromptPanelLogic.suggest("/", allTriggers)))
        assertEquals(listOf("Shorter"), names(PromptPanelLogic.suggest("/sh", allTriggers)))
        val longList = (1..5).flatMap { allTriggers }
        assertEquals(6, PromptPanelLogic.suggest("/", longList, limit = 6).size)
    }

    @Test
    fun testNoListForOneLetterOrdinaryInstructionOrChosenTrigger() {
        assertTrue(PromptPanelLogic.suggest("", allTriggers).isEmpty())
        assertTrue(PromptPanelLogic.suggest("p", allTriggers).isEmpty())
        assertTrue(PromptPanelLogic.suggest("make it more formal", allTriggers).isEmpty())
        assertTrue(PromptPanelLogic.suggest("prompt ticket", allTriggers).isEmpty()) // whole phrase chosen
        assertTrue(PromptPanelLogic.suggest("prompt ticket the login page", allTriggers).isEmpty())
        assertTrue(PromptPanelLogic.suggest("first line\nprompt", allTriggers).isEmpty())
        assertTrue(PromptPanelLogic.suggest("pro" + "x".repeat(50), allTriggers).isEmpty())
        assertTrue(PromptPanelLogic.suggest("pro", emptyList()).isEmpty())
        assertTrue(PromptPanelLogic.suggest("pro", null).isEmpty())
    }

    @Test
    fun testAcceptingSuggestionPutsFirstPhraseInBox() {
        assertEquals("prompt ticket ", PromptPanelLogic.completion(ticket))
        assertTrue(PromptPanelLogic.suggest(PromptPanelLogic.completion(ticket), allTriggers).isEmpty())
    }

    @Test
    fun testChosenFindsPhraseAnywhereInTypedText() {
        assertSame(ticket, PromptPanelLogic.chosen("prompt ticket the login page crashes", allTriggers))
        assertSame(ticket, PromptPanelLogic.chosen("The login page crashes. Ticket this!", allTriggers))
        assertSame(standup, PromptPanelLogic.chosen("PROMPT  stand-up yesterday I shipped", allTriggers))
        assertNull(PromptPanelLogic.chosen("make it shorter", allTriggers))
        assertNull(PromptPanelLogic.chosen("prompt tickets", allTriggers)) // not whole word
    }

    @Test
    fun testIncludeLabelWordCounts() {
        assertEquals("Include the highlighted text (3 words)", PromptPanelLogic.includeLabel("see you tomorrow"))
        assertEquals("Include the highlighted text (1 word)", PromptPanelLogic.includeLabel("hello"))
        assertEquals("Include the highlighted text (0 words)", PromptPanelLogic.includeLabel(""))
    }

    @Test
    fun testF62SkillsWordingAndSummaries() {
        assertEquals("Type an instruction, or / for your skills", PromptPanelLogic.PLACEHOLDER)

        val (body, example) = PromptPanelLogic.askAiLines("prompt AI")
        assertEquals("Say \"prompt AI\", then what you want.", body)
        assertEquals("\"Prompt AI, make this sound more formal.\"", example)

        val (noPhraseBody, noPhraseExample) = PromptPanelLogic.askAiLines("")
        assertEquals(PromptPanelLogic.ASK_CARD_NO_PHRASE, noPhraseBody)
        assertEquals("", noPhraseExample)

        assertEquals("say \"skill email\"", PromptPanelLogic.skillSummary(listOf("skill email")))
        assertEquals("say \"skill email\" (+2 more)", PromptPanelLogic.skillSummary(listOf("skill email", "prompt email", "write email")))
        assertEquals("", PromptPanelLogic.skillSummary(emptyList()))

        assertEquals(
            "Skills: \"prompt ticket\" is already one of your phrases for asking the AI.",
            PromptPanelLogic.skillWording("Custom triggers: \"prompt ticket\" is already a built-in trigger phrase.")
        )
    }
}
