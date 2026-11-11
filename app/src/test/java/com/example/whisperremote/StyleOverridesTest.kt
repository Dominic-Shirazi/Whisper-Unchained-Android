// This file provides unit tests for StyleOverrides used by FloatingButtonService and ApiClient.
// It is called by Gradle test runner during ./gradlew test execution.
// Inputs: StyleOverrides data objects configured with rewrite, shorten, punctuation, and tone.
// Outputs: Assertion verifications confirming defaults, values, and immutability per contracts F-6 & F-7.

package com.example.whisperremote

// Reason for edit: Added unit tests for StyleOverrides per contracts F-6 and F-7.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StyleOverridesTest {

    @Test
    fun testStyleOverridesDefaults() {
        val overrides = StyleOverrides(
            rewrite = "fix",
            shorten = "light",
            punctuation = "standard"
        )
        assertEquals("fix", overrides.rewrite)
        assertEquals("light", overrides.shorten)
        assertEquals("standard", overrides.punctuation)
        assertEquals("neutral", overrides.tone) // Default tone per contract 02 & F-7
    }

    @Test
    fun testStyleOverridesCustomTone() {
        val overrides = StyleOverrides(
            rewrite = "polish",
            shorten = "strong",
            punctuation = "minimal",
            tone = "professional"
        )
        assertEquals("polish", overrides.rewrite)
        assertEquals("strong", overrides.shorten)
        assertEquals("minimal", overrides.punctuation)
        assertEquals("professional", overrides.tone)
    }

    @Test
    fun testStyleOverridesEquality() {
        val o1 = StyleOverrides("keep", "off", "standard", "neutral")
        val o2 = StyleOverrides("keep", "off", "standard", "neutral")
        val o3 = StyleOverrides("fix", "off", "standard", "neutral")

        assertEquals(o1, o2)
        assertNotEquals(o1, o3)
    }
}
