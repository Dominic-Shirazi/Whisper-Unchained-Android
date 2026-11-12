// This file tests helper words and name corrections editing, validation, and queueing.
// It is called by the Gradle unit test runner during build verification.
// Inputs: Word strings, name correction pairs, and queued mutations.
// Outputs: Verifies client-side validation, queued mutations serialization, and immediate local application.
// Conforms to contracts/01-transcription-api.md §8 and F-43.
// Reason for edit: Add 401, 503, and 2xx parse error poison-pill unit tests per F-46.
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

    // Reason for edit: Add tests for OpenAI error parsing, replay guard, and offline calculation per F-44
    @Test
    fun testExtractErrorMessage_openAiStyle() {
        val json = """{"error": {"message": "'Cory' would be replaced by itself.", "type": "invalid_request_error", "code": "invalid_name_corrections"}}"""
        val extracted = ApiClient.extractErrorMessage(json)
        assertEquals("'Cory' would be replaced by itself.", extracted)
    }

    @Test
    fun testExtractErrorMessage_fallbackToTopLevel() {
        val topMsgJson = """{"message": "Word cannot be empty"}"""
        assertEquals("Word cannot be empty", ApiClient.extractErrorMessage(topMsgJson))

        val detailJson = """{"detail": "Bad payload"}"""
        assertEquals("Bad payload", ApiClient.extractErrorMessage(detailJson))
    }

    @Test
    fun testOfflineComputation_fromNameCorrectionsAndChars() {
        val vocab = listOf("Kubernetes", "Docker", "Ollama")
        val corrections = mapOf("Corey" to "Cory", "Emma" to "Ame", "Leslie" to "Lesley")

        // fromNameCorrections = distinct values of name corrections
        val fromNames = corrections.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        assertEquals(listOf("Cory", "Ame", "Lesley"), fromNames)

        // chars = length of ", ".join(distinct(vocabulary + those values))
        val combined = (vocab + fromNames).distinct()
        val chars = combined.joinToString(", ").length
        assertEquals("Kubernetes, Docker, Ollama, Cory, Ame, Lesley".length, chars)
    }

    @Test
    fun testReplayPendingMutations_concurrentGuard() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            repository.baseUrl = server.url("/v1").toString()
            val apiClient = ApiClient(context)

            val m1 = QueuedMutation(id = "m1", type = "vocab", removeWords = listOf("Docker"))
            val m2 = QueuedMutation(id = "m2", type = "vocab", addWords = listOf("Docker"))
            repository.addPendingMutation(m1)
            repository.addPendingMutation(m2)

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"vocabulary":[],"from_name_corrections":[],"chars":0,"max_chars":540}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"vocabulary":["Docker"],"from_name_corrections":[],"chars":6,"max_chars":540}"""))

            val latch = java.util.concurrent.CountDownLatch(1)
            var secondCallAccepted: Boolean? = null

            // Call replay twice back to back
            apiClient.replayPendingMutations { _ ->
                latch.countDown()
            }
            apiClient.replayPendingMutations { accepted ->
                secondCallAccepted = accepted
            }

            // Second call must be rejected immediately while first replay is active
            assertEquals(false, secondCallAccepted)

            val start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(0, latch.count)

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()

            assertTrue(req1.body.readUtf8().contains(""""remove":["Docker"]"""))
            assertTrue(req2.body.readUtf8().contains(""""add":["Docker"]"""))
            assertEquals(0, repository.getPendingMutations().size)
        } finally {
            server.shutdown()
        }
    }

    // Reason for edit: Add tests for F-45 replay hardening (empty item dropping, early success, 3-strike drop)
    @Test
    fun testModify_earlyReturnOnEmptyCallsBackSuccess() {
        val apiClient = ApiClient(context)
        var vocabCallbackCalled = false
        apiClient.modifyVocabulary(emptyList(), emptyList()) { res ->
            if (res is VocabularyResult.Success) vocabCallbackCalled = true
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(vocabCallbackCalled)

        var nameCallbackCalled = false
        apiClient.modifyNameCorrections(emptyMap(), emptyList()) { res ->
            if (res is NameCorrectionsResult.Success) nameCallbackCalled = true
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(nameCallbackCalled)
    }

    @Test
    fun testReplayPendingMutations_dropsEmptyItems() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            repository.baseUrl = server.url("/v1").toString()
            val apiClient = ApiClient(context)

            // Queue an empty mutation followed by a valid mutation
            val mEmpty = QueuedMutation(id = "mEmpty", type = "vocab", addWords = emptyList(), removeWords = emptyList())
            val mValid = QueuedMutation(id = "mValid", type = "vocab", addWords = listOf("Ollama"))
            repository.addPendingMutation(mEmpty)
            repository.addPendingMutation(mValid)

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"vocabulary":["Ollama"],"from_name_corrections":[],"chars":6,"max_chars":540}"""))

            val latch = java.util.concurrent.CountDownLatch(1)
            apiClient.replayPendingMutations { _ -> latch.countDown() }

            val start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(0, latch.count)

            // Server only received 1 request (for the valid mutation)
            assertEquals(1, server.requestCount)
            val req = server.takeRequest()
            assertTrue(req.body.readUtf8().contains(""""add":["Ollama"]"""))
            assertEquals(0, repository.getPendingMutations().size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testReplayPendingMutations_200WithGarbageDropsOnlyPoisonPillAndSendsNext() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            repository.baseUrl = server.url("/v1").toString()
            val apiClient = ApiClient(context)

            val mBad = QueuedMutation(id = "mBad", type = "vocab", addWords = listOf("PoisonPill"))
            val mGood = QueuedMutation(id = "mGood", type = "vocab", addWords = listOf("Ollama"))
            repository.addPendingMutation(mBad)
            repository.addPendingMutation(mGood)

            // Respond 3 times with malformed JSON for mBad, then valid JSON for mGood
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("Not Valid JSON 1"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("Not Valid JSON 2"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("Not Valid JSON 3"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"vocabulary":["Ollama"],"from_name_corrections":[],"chars":6,"max_chars":540}"""))

            // Attempt 1 for mBad
            var latch = java.util.concurrent.CountDownLatch(1)
            apiClient.replayPendingMutations { _ -> latch.countDown() }
            var start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(2, repository.getPendingMutations().size)
            assertEquals("mBad", repository.getPendingMutations()[0].id)
            assertEquals(1, repository.getPendingMutations()[0].attempts)

            // Attempt 2 for mBad
            latch = java.util.concurrent.CountDownLatch(1)
            apiClient.replayPendingMutations { _ -> latch.countDown() }
            start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(2, repository.getPendingMutations().size)
            assertEquals("mBad", repository.getPendingMutations()[0].id)
            assertEquals(2, repository.getPendingMutations()[0].attempts)

            // Attempt 3 for mBad -> drops mBad and automatically replays mGood
            latch = java.util.concurrent.CountDownLatch(1)
            apiClient.replayPendingMutations { _ -> latch.countDown() }
            start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            // All mutations processed: mBad was dropped on 3rd attempt, and mGood succeeded!
            assertEquals(0, repository.getPendingMutations().size)
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // Reason for edit: Verify 401 Unauthorized keeps the mutation queue intact without counting strikes per F-46
    @Test
    fun testReplay_401DoesNotDropEdits() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            repository.baseUrl = server.url("/v1").toString()
            val apiClient = ApiClient(context)

            val m1 = QueuedMutation(id = "m1", type = "vocab", addWords = listOf("Kubernetes"))
            repository.addPendingMutation(m1)

            // 5 consecutive 401 Unauthorized responses
            for (i in 1..5) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid token"}}"""))
            }

            for (i in 1..5) {
                val latch = java.util.concurrent.CountDownLatch(1)
                apiClient.replayPendingMutations { _ -> latch.countDown() }
                val start = System.currentTimeMillis()
                while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                assertEquals(0, latch.count)
                // Queue intact, zero strikes recorded
                assertEquals(1, repository.getPendingMutations().size)
                assertEquals(0, repository.getPendingMutations()[0].attempts)
            }

            // authErrorNotified was set to true
            assertTrue(apiClient.authErrorNotified.get())
        } finally {
            server.shutdown()
        }
    }

    // Reason for edit: Verify 503 Service Unavailable keeps the mutation queue intact without counting strikes per F-46
    @Test
    fun testReplay_503DoesNotDropEdits() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            repository.baseUrl = server.url("/v1").toString()
            val apiClient = ApiClient(context)

            val m1 = QueuedMutation(id = "m1", type = "vocab", addWords = listOf("Kubernetes"))
            repository.addPendingMutation(m1)

            // 5 consecutive 503 Service Unavailable responses
            for (i in 1..5) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("Service Temporarily Unavailable"))
            }

            for (i in 1..5) {
                val latch = java.util.concurrent.CountDownLatch(1)
                apiClient.replayPendingMutations { _ -> latch.countDown() }
                val start = System.currentTimeMillis()
                while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                assertEquals(0, latch.count)
                // Queue intact, zero strikes recorded
                assertEquals(1, repository.getPendingMutations().size)
                assertEquals(0, repository.getPendingMutations()[0].attempts)
            }
        } finally {
            server.shutdown()
        }
    }
}
