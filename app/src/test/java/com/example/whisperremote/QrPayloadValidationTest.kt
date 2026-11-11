// This file tests QR code pairing payload validation per contract F-12.
// Inputs: JSON payloads representing valid and invalid server pairings.
// Outputs: Verifies scheme (http/https), host validation, /v1 appending, and extraction of hostDisplay.

package com.example.whisperremote

// Reason for edit: Unit test for QR pairing payload validation and host confirmation per contract F-12.

import com.example.whisperremote.data.SettingsRepository
import org.junit.Assert.*
import org.junit.Test

class QrPayloadValidationTest {

    @Test
    fun testValidHttpPayloadWithV1() {
        val json = """{"v":1,"base_url":"http://100.89.163.64:5000/v1","api_key":"secret123","model":"large-v3-turbo"}"""
        val payload = SettingsRepository.parseAndValidateQrPayload(json)
        assertNotNull(payload)
        assertEquals("http://100.89.163.64:5000/v1", payload?.baseUrl)
        assertEquals("secret123", payload?.apiKey)
        assertEquals("large-v3-turbo", payload?.model)
        assertEquals("100.89.163.64:5000", payload?.hostDisplay)
    }

    @Test
    fun testValidHttpPayloadAppendsV1() {
        val json = """{"v":1,"base_url":"http://100.89.163.64:5000","api_key":"","model":"whisper-1"}"""
        val payload = SettingsRepository.parseAndValidateQrPayload(json)
        assertNotNull(payload)
        assertEquals("http://100.89.163.64:5000/v1", payload?.baseUrl)
        assertEquals("100.89.163.64:5000", payload?.hostDisplay)
    }

    @Test
    fun testValidHttpsPayload() {
        val json = """{"v":1,"base_url":"https://api.groq.com/openai/v1","api_key":"gsk_test"}"""
        val payload = SettingsRepository.parseAndValidateQrPayload(json)
        assertNotNull(payload)
        assertEquals("https://api.groq.com/openai/v1", payload?.baseUrl)
        assertEquals("api.groq.com", payload?.hostDisplay)
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
        val jsonBadVersion = """{"v":2,"base_url":"http://100.89.163.64:5000/v1"}"""
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonBadVersion))

        val jsonGarbage = "not a json string"
        assertNull(SettingsRepository.parseAndValidateQrPayload(jsonGarbage))
    }
}
