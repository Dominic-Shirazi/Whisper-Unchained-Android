// This file contains unit tests for KeyStoreCryptoHelper.
// It is called by the Gradle test runner during ./gradlew test.
// Inputs: Plaintext and mock cipher strings.
// Outputs: Verifies prefix detection, transparent backward compatibility for unencrypted values, and empty handling.

package com.example.whisperremote

// Reason for edit: Added unit tests for KeyStoreCryptoHelper fallback, prefix handling, and backward compatibility.

import com.example.whisperremote.util.KeyStoreCryptoHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyStoreCryptoHelperTest {

    @Test
    fun testDecryptPlaintextReturnsOriginal() {
        val original = "http://100.115.92.2:5000/v1"
        val decrypted = KeyStoreCryptoHelper.decrypt(original)
        assertEquals(original, decrypted)
    }

    @Test
    fun testEmptyPlaintextHandling() {
        val encrypted = KeyStoreCryptoHelper.encrypt("")
        assertEquals("", encrypted)

        val decrypted = KeyStoreCryptoHelper.decrypt("")
        assertEquals("", decrypted)
    }

    @Test
    fun testBackwardCompatibleWithUnprefixedSecrets() {
        val secret = "wr_sec_super_secret_token_12345"
        val decrypted = KeyStoreCryptoHelper.decrypt(secret)
        assertEquals(secret, decrypted)
    }

    @Test
    fun testMigratedCleanupPunctuationReadsBackAsStandard() {
        val plain = "standard"
        val decrypted = KeyStoreCryptoHelper.decrypt(plain)
        assertEquals("standard", decrypted)
    }
}
