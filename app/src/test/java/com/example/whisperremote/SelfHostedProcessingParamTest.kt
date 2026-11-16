// This file tests that self-hosted transcription requests strictly send the `processing` field.
// It is called by the unit test runner (`./gradlew testPlayDebugUnitTest`).
// Inputs: ApiClient transcribeDetailed calls with various configurations and overrides.
// Outputs: Verifies presence and exact value of `processing` form field in HTTP request body per F-49.

package com.example.whisperremote

// Reason for creation: Unit test verifying `processing` form field is always sent on self-hosted transcription per F-49.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfHostedProcessingParamTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository
    private lateinit var apiClient: ApiClient
    private lateinit var server: MockWebServer
    private lateinit var dummyAudioFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        server = MockWebServer()
        server.start()

        repository.providerPreset = ProviderPreset.SELF_HOSTED
        val serverUrl = server.url("/v1").toString()
        repository.baseUrl = serverUrl
        repository.lastWorkingBaseUrl = serverUrl

        apiClient = ApiClient(context)

        dummyAudioFile = tempFolder.newFile("test_audio.wav")
        dummyAudioFile.writeBytes(ByteArray(100))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun waitForCallback(latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 5000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    @Test
    fun testSelfHostedDefaultSendsProcessingFull() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello world"}"""))
        repository.cleanupEnabled = false
        repository.processingMode = "full"

        val latch = CountDownLatch(1)
        var resultText: String? = null

        apiClient.transcribeDetailed(dummyAudioFile) { res ->
            if (res is TranscriptionResult.Success) {
                resultText = res.text
            }
            latch.countDown()
        }

        waitForCallback(latch)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val body = recorded!!.body.readUtf8()
        assertTrue("Request body must contain name=\"processing\"", body.contains("name=\"processing\""))
        assertTrue("Request body must contain value \"full\"", body.contains("full"))
    }

    @Test
    fun testSelfHostedCleanupEnabledSendsProcessingClean() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello cleaned"}"""))
        repository.cleanupEnabled = true

        val latch = CountDownLatch(1)
        apiClient.transcribeDetailed(dummyAudioFile) {
            latch.countDown()
        }

        waitForCallback(latch)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val body = recorded!!.body.readUtf8()
        assertTrue("Request body must contain name=\"processing\"", body.contains("name=\"processing\""))
        assertTrue("Request body must contain \"clean\"", body.contains("clean"))
    }

    @Test
    fun testSelfHostedWithTriggerSendsTriggerField() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ticket result"}"""))
        repository.cleanupEnabled = false

        val latch = CountDownLatch(1)
        apiClient.transcribeDetailed(dummyAudioFile, trigger = "bug-ticket") {
            latch.countDown()
        }

        waitForCallback(latch)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val body = recorded!!.body.readUtf8()
        assertTrue("Request body must contain name=\"trigger\"", body.contains("name=\"trigger\""))
        assertTrue("Request body must contain trigger id", body.contains("bug-ticket"))
    }
}
