// This file tests helper words and name corrections editing, validation, and queueing.
// It is called by the Gradle unit test runner during build verification.
// Inputs: Word strings, name correction pairs, and queued mutations.
// Outputs: Verifies client-side validation, queued mutations serialization, and immediate local application.
// Conforms to contracts/01-transcription-api.md §8 and F-43.
// Reason for creation: Unit test suite for helper words & name corrections management per F-43.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.QueuedMutation
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.util.TextRules
import com.example.whisperremote.util.WordRulesValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordListManagementTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        repository.clearPendingMutations()
        repository.cachedServerVocabulary = emptySet()
        repository.cachedServerNameCorrectionsJson = ""
    }

    @Test
    fun testValidateHelperWord_valid() {
        assertNull(WordRulesValidator.validateHelperWord("Kubernetes"))
        assertNull(WordRulesValidator.validateHelperWord("Whisper Unchained"))
        assertNull(WordRulesValidator.validateHelperWord("Ollama"))
    }

    @Test
    fun testValidateHelperWord_invalid() {
        assertNotNull(WordRulesValidator.validateHelperWord(""))
        assertNotNull(WordRulesValidator.validateHelperWord("   "))
        assertNotNull(WordRulesValidator.validateHelperWord("a".repeat(60))) // >= 60 chars
        assertNotNull(WordRulesValidator.validateHelperWord("Hello, World")) // comma
        assertNotNull(WordRulesValidator.validateHelperWord("Hello\nWorld")) // newline
        assertNotNull(WordRulesValidator.validateHelperWord("Hello\rWorld")) // return
        assertNotNull(WordRulesValidator.validateHelperWord("Docker", existingWords = listOf("Docker", "Podman")))
    }

    @Test
    fun testValidateNameCorrection_valid() {
        assertNull(WordRulesValidator.validateNameCorrection("Corey", "Cory"))
        assertNull(WordRulesValidator.validateNameCorrection("Leslie", "Lesley"))
        assertNull(WordRulesValidator.validateNameCorrection("AI", "A.I."))
    }

    @Test
    fun testValidateNameCorrection_invalid() {
        assertNotNull(WordRulesValidator.validateNameCorrection("C", "Cory")) // heard-as < 2 chars
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey", "")) // empty write-as
        assertNotNull(WordRulesValidator.validateNameCorrection("a".repeat(60), "Cory")) // >= 60 chars
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey", "a".repeat(60))) // >= 60 chars
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey, Jr.", "Cory")) // comma
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey", "Cory, Jr.")) // comma
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey\nSmith", "Cory")) // newline
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey", "corey")) // identical case-insensitive
        assertNotNull(WordRulesValidator.validateNameCorrection("Corey", "Cory", existingHeardList = listOf("Corey", "Emma")))
    }

    @Test
    fun testQueuedMutation_serialization() {
        val mutation = QueuedMutation(
            id = "test-123",
            type = "name",
            setNameCorrections = mapOf("Corey" to "Cory"),
            removeNameCorrections = listOf("Emma")
        )
        val json = mutation.toJson()
        val deserialized = QueuedMutation.fromJson(json)

        assertEquals("test-123", deserialized.id)
        assertEquals("name", deserialized.type)
        assertEquals("Cory", deserialized.setNameCorrections["Corey"])
        assertEquals(listOf("Emma"), deserialized.removeNameCorrections)
    }

    @Test
    fun testImmediateLocalApplication_nameCorrections() {
        repository.cachedServerNameCorrectionsJson = "{\"Emma\":\"Ame\"}"

        val mutation = QueuedMutation(
            type = "name",
            setNameCorrections = mapOf("Corey" to "Cory"),
            removeNameCorrections = listOf("Emma")
        )
        repository.queueWordMutation(mutation)

        val updatedMap = repository.parseCachedServerNameCorrections()
        assertEquals("Cory", updatedMap["Corey"])
        assertNull(updatedMap["Emma"])
        assertTrue(repository.isNameCorrectionPending("Corey"))
        assertTrue(repository.isNameCorrectionPending("Emma"))

        // Verify TextRules immediately applies it
        val rawText = "Hello Corey and Emma"
        val corrected = TextRules.applyNameCorrections(rawText, updatedMap)
        assertEquals("Hello Cory and Emma", corrected)
    }

    @Test
    fun testImmediateLocalApplication_helperWords() {
        repository.cachedServerVocabulary = setOf("Docker")

        val mutation = QueuedMutation(
            type = "vocab",
            addWords = listOf("Kubernetes"),
            removeWords = listOf("Docker")
        )
        repository.queueWordMutation(mutation)

        assertTrue(repository.cachedServerVocabulary.contains("Kubernetes"))
        assertFalse(repository.cachedServerVocabulary.contains("Docker"))
        assertTrue(repository.isVocabPending("Kubernetes"))
        assertTrue(repository.isVocabPending("Docker"))
    }

    @Test
    fun testFormatCorrectionsText() {
        val map = mapOf("Leslie" to "Lesley", "Corey" to "Cory")
        val formatted = TextRules.formatCorrectionsText(map)
        assertTrue(formatted.contains("Leslie: Lesley"))
        assertTrue(formatted.contains("Corey: Cory"))

        val parsed = TextRules.parseCorrectionsText(formatted)
        assertEquals("Lesley", parsed["Leslie"])
        assertEquals("Cory", parsed["Corey"])
    }
}
