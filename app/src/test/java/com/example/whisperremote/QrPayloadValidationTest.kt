// This file tests QR code pairing payload validation per contract F-12.
// Inputs: JSON payloads representing valid and invalid server pairings.
// Outputs: Verifies scheme (http/https), host validation, /v1 appending, and extraction of hostDisplay.

package com.example.whisperremote

// Reason for edit: Unit test for QR pairing payload validation and host confirmation per contract F-12.
// Reason for edit: Updated for contract 06 §2 (payload v2 with pinned cert, Play flavor rejection of v1).

import com.example.whisperremote.data.SettingsRepository
import org.junit.Assert.*
import org.junit.Test

class QrPayloadValidationTest {

    @Test
    fun testValidHttpPayloadWithV1() {
        val json = """{"v":1,"base_url":"http://100.89.163.64:5000/v1","api_key":"secret123","model":"large-v3-turbo"}"""
        if (BuildConfig.FLAVOR == "play") {
            try {
                SettingsRepository.parseAndValidateQrPayload(json)
                fail("Expected IllegalArgumentException in play flavor for v1 payload")
            } catch (e: IllegalArgumentException) {
                assertEquals("This pairing code is from an older server. Update Whisper Unchained Server, then scan again.", e.message)
            }
        } else {
            val payload = SettingsRepository.parseAndValidateQrPayload(json)
            assertNotNull(payload)
            assertEquals("http://100.89.163.64:5000/v1", payload?.baseUrl)
            assertEquals("secret123", payload?.apiKey)
            assertEquals("large-v3-turbo", payload?.model)
            assertEquals("100.89.163.64:5000", payload?.hostDisplay)
        }
    }

    @Test
    fun testValidHttpPayloadAppendsV1() {
        val json = """{"v":1,"base_url":"http://100.89.163.64:5000","api_key":"","model":"whisper-1"}"""
        if (BuildConfig.FLAVOR == "play") {
            try {
                SettingsRepository.parseAndValidateQrPayload(json)
                fail("Expected IllegalArgumentException in play flavor for v1 payload")
            } catch (e: IllegalArgumentException) {
                assertEquals("This pairing code is from an older server. Update Whisper Unchained Server, then scan again.", e.message)
            }
        } else {
            val payload = SettingsRepository.parseAndValidateQrPayload(json)
            assertNotNull(payload)
            assertEquals("http://100.89.163.64:5000/v1", payload?.baseUrl)
            assertEquals("100.89.163.64:5000", payload?.hostDisplay)
        }
    }

    @Test
    fun testValidHttpsPayload() {
        val json = """{"v":1,"base_url":"https://api.groq.com/openai/v1","api_key":"gsk_test"}"""
        if (BuildConfig.FLAVOR == "play") {
            try {
                SettingsRepository.parseAndValidateQrPayload(json)
                fail("Expected IllegalArgumentException in play flavor for v1 payload")
            } catch (e: IllegalArgumentException) {
                assertEquals("This pairing code is from an older server. Update Whisper Unchained Server, then scan again.", e.message)
            }
        } else {
            val payload = SettingsRepository.parseAndValidateQrPayload(json)
            assertNotNull(payload)
            assertEquals("https://api.groq.com/openai/v1", payload?.baseUrl)
            assertEquals("api.groq.com", payload?.hostDisplay)
        }
    }

    @Test
    fun testValidV2HttpsPayloadWithPin() {
        val pin = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val json = """{"v":2,"base_url":"https://100.89.163.64:5000/v1","cert_sha256":"$pin","api_key":"secret123","model":"large-v3-turbo","name":"My-PC"}"""
        val payload = SettingsRepository.parseAndValidateQrPayload(json)
        assertNotNull(payload)
        assertEquals("https://100.89.163.64:5000/v1", payload?.baseUrl)
        assertEquals("secret123", payload?.apiKey)
        assertEquals("large-v3-turbo", payload?.model)
        assertEquals("My-PC", payload?.name)
        assertEquals(pin, payload?.certSha256)
        assertEquals("100.89.163.64:5000", payload?.hostDisplay)
        assertEquals(2, payload?.version)
    }

    @Test
    fun testInvalidV2Payload() {
        // v2 with http rejected
        val jsonHttp = """{"v":2,"base_url":"http://100.89.163.64:5000/v1","cert_sha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}"""
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonHttp))

        // v2 with invalid pin format (too short) rejected
        val jsonShortPin = """{"v":2,"base_url":"https://100.89.163.64:5000/v1","cert_sha256":"short"}"""
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonShortPin))
    }

    @Test
    fun testInvalidSchemeRejected() {
        val json = """{"v":1,"base_url":"ftp://100.89.163.64:5000/v1"}"""
        val payload = SettingsRepository.parseAndValidateQrPayload(json)
        assertNull(payload)

        val jsonFile = """{"v":1,"base_url":"file:///etc/hosts"}"""
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonFile))
    }

    @Test
    fun testMissingHostRejected() {
        val json = """{"v":1,"base_url":"http:///v1"}"""
        val payload = SettingsRepository.parseAndValidateQrPayload(json)
        assertNull(payload)
    }

    @Test
    fun testInvalidVersionOrMalformedJson() {
        val jsonBadVersion = """{"v":3,"base_url":"https://100.89.163.64:5000/v1"}"""
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonBadVersion))

        val jsonGarbage = "not a json string"
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonGarbage))
    }
}
