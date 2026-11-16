// This file tests custom triggers JSON parsing, trigger limits, library item parsing, and library states.
// It is called by Gradle test runners.
// Inputs: JSON payloads representing GET /v1/custom_triggers responses and trigger_library/library.json.
// Outputs: Verifies CustomTrigger model deserialization, library state calculation (Add, Added, Update), and payload mutations.
// Conforms to contracts/01-transcription-api.md §11 and F-53.

package com.example.whisperremote

import com.example.whisperremote.data.CustomTrigger
import com.example.whisperremote.data.TriggerLimits
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTriggersParsingAndLibraryTest {

    @Test
    fun testParseCustomTriggersResponse() {
        val jsonStr = """
            {
                "triggers": [
                    {
                        "id": "ticket",
                        "name": "Bug ticket",
                        "phrases": ["prompt ticket", "ticket this"],
                        "prompt": "Rewrite as bug ticket",
                        "enabled": true,
                        "library_id": "ticket",
                        "library_version": 1,
                        "author": "Whisper Unchained",
                        "description": "Bug description becomes ticket",
                        "category": "Work writing",
                        "model": "any"
                    },
                    {
                        "id": "custom-1",
                        "name": "My Custom Trigger",
                        "phrases": ["do something"],
                        "prompt": "Custom instruction prompt",
                        "enabled": false
                    }
                ],
                "limits": {
                    "triggers": 100,
                    "name": 40,
                    "phrases": 6,
                    "phrase": 60,
                    "prompt_min": 10,
                    "prompt_max": 6000
                }
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val triggersArray = json.getJSONArray("triggers")
        val triggers = (0 until triggersArray.length()).map {
            CustomTrigger.fromJson(triggersArray.getJSONObject(it))
        }

        assertEquals(2, triggers.size)

        val t1 = triggers[0]
        assertEquals("ticket", t1.id)
        assertEquals("Bug ticket", t1.name)
        assertEquals(listOf("prompt ticket", "ticket this"), t1.phrases)
        assertEquals("Rewrite as bug ticket", t1.prompt)
        assertTrue(t1.enabled)
        assertEquals("ticket", t1.libraryId)
        assertEquals(1, t1.libraryVersion)
        assertEquals("Whisper Unchained", t1.author)
        assertEquals("Bug description becomes ticket", t1.description)
        assertEquals("Work writing", t1.category)
        assertEquals("any", t1.model)

        val t2 = triggers[1]
        assertEquals("custom-1", t2.id)
        assertEquals("My Custom Trigger", t2.name)
        assertEquals(listOf("do something"), t2.phrases)
        assertFalse(t2.enabled)

        val limits = TriggerLimits.fromJson(json.getJSONObject("limits"))
        assertEquals(100, limits.triggers)
        assertEquals(40, limits.nameMax)
        assertEquals(6, limits.phrasesMax)
        assertEquals(60, limits.phraseMax)
        assertEquals(10, limits.promptMin)
        assertEquals(6000, limits.promptMax)
    }

    @Test
    fun testParseLibraryJsonAndStates() {
        val libraryJsonStr = """
            {
                "version": 1,
                "updated": "2026-11-16",
                "triggers": [
                    {
                        "id": "ticket",
                        "name": "Bug ticket",
                        "phrases": ["prompt ticket", "ticket this"],
                        "prompt": "Rewrite as bug ticket",
                        "author": "Whisper Unchained",
                        "description": "Bug description becomes ticket",
                        "model": "any",
                        "version": 2,
                        "category": "Work writing"
                    },
                    {
                        "id": "email",
                        "name": "Email from notes",
                        "phrases": ["prompt email"],
                        "prompt": "Turn into email",
                        "author": "Whisper Unchained",
                        "description": "Say the gist; get an email",
                        "model": "any",
                        "version": 1,
                        "category": "Work writing"
                    },
                    {
                        "id": "spec",
                        "name": "Feature spec",
                        "phrases": ["prompt spec"],
                        "prompt": "Rewrite as spec",
                        "author": "Whisper Unchained",
                        "description": "Feature spec",
                        "model": "strong",
                        "version": 1,
                        "category": "Prompts for AI"
                    }
                ]
            }
        """.trimIndent()

        val json = JSONObject(libraryJsonStr)
        val arr = json.getJSONArray("triggers")
        val libraryTriggers = (0 until arr.length()).map {
            CustomTrigger.fromJson(arr.getJSONObject(it))
        }

        assertEquals(3, libraryTriggers.size)

        // Existing user triggers:
        // 1. "ticket" is installed with version 1 (library has version 2 -> UPDATE)
        // 2. "email" is installed with version 1 (library has version 1 -> ADDED)
        // 3. "spec" is not installed (-> ADD)
        val userTriggers = listOf(
            CustomTrigger(
                id = "ticket",
                name = "Bug ticket",
                phrases = listOf("prompt ticket"),
                prompt = "Old prompt",
                libraryId = "ticket",
                libraryVersion = 1
            ),
            CustomTrigger(
                id = "email",
                name = "Email from notes",
                phrases = listOf("prompt email"),
                prompt = "Turn into email",
                libraryId = "email",
                libraryVersion = 1
            )
        )

        // Check state for "ticket": library version 2 > user version 1 -> Needs Update
        val ticketLib = libraryTriggers.first { it.id == "ticket" }
        val userTicket = userTriggers.firstOrNull { it.id == ticketLib.id || it.libraryId == ticketLib.id }
        assertNotNull(userTicket)
        val ticketNeedsUpdate = (ticketLib.libraryVersion ?: 1) > (userTicket?.libraryVersion ?: 1)
        assertTrue(ticketNeedsUpdate)

        // Check state for "email": library version 1 == user version 1 -> Added (Up to date)
        val emailLib = libraryTriggers.first { it.id == "email" }
        val userEmail = userTriggers.firstOrNull { it.id == emailLib.id || it.libraryId == emailLib.id }
        assertNotNull(userEmail)
        val emailNeedsUpdate = (emailLib.libraryVersion ?: 1) > (userEmail?.libraryVersion ?: 1)
        assertFalse(emailNeedsUpdate)

        // Check state for "spec": not installed -> Add
        val specLib = libraryTriggers.first { it.id == "spec" }
        val userSpec = userTriggers.firstOrNull { it.id == specLib.id || it.libraryId == specLib.id }
        assertTrue(userSpec == null)
    }

    @Test
    fun testTriggerToJsonPayload() {
        val trigger = CustomTrigger(
            id = "test-trigger",
            name = "Test Trigger",
            phrases = listOf("prompt test", "run test"),
            prompt = "Do test stuff",
            enabled = true,
            libraryId = "lib-1",
            libraryVersion = 2,
            author = "Dev"
        )

        val json = trigger.toJson()
        assertEquals("test-trigger", json.getString("id"))
        assertEquals("Test Trigger", json.getString("name"))
        val phrases = json.getJSONArray("phrases")
        assertEquals(2, phrases.length())
        assertEquals("prompt test", phrases.getString(0))
        assertEquals("run test", phrases.getString(1))
        assertEquals("Do test stuff", json.getString("prompt"))
        assertTrue(json.getBoolean("enabled"))
        assertEquals("lib-1", json.getString("library_id"))
        assertEquals(2, json.getInt("library_version"))
        assertEquals("Dev", json.getString("author"))
    }
}
