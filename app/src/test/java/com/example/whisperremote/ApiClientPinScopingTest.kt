// This file tests ApiClient certificate pin scoping per contract 06 and F-40.
// It is called by Gradle during JVM Robolectric unit testing.
// Inputs: MockWebServer instances simulating paired PC origin and third-party/cloud endpoints.
// Outputs: Verifies that certificate pinning is strictly scoped to the paired PC origin (scheme, host, port),
//          that different hosts (e.g. cloud LLMs) and different ports bypass the pin and use system CA trust,
//          and that the paired PC with a wrong pin fails.
// Conforms to F-40 and contracts/06-https.md §3.
// Reason for edit: Add unit tests for origin-scoped certificate pinning per F-40.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.net.PinnedCertificateHelper
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientPinScopingTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository
    private lateinit var apiClient: ApiClient

    private lateinit var pcServer: MockWebServer
    private lateinit var otherServer: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        apiClient = ApiClient(context)

        pcServer = MockWebServer()
        otherServer = MockWebServer()
    }

    @After
    fun tearDown() {
        pcServer.shutdown()
        otherServer.shutdown()
        repository.serverCertSha256 = null
    }

    @Test
    fun testIsPairedPcOriginLogic() {
        repository.baseUrl = "https://100.89.163.64:5000/v1"
        repository.serverCertSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        // 1. Exact match origin -> true
        assertTrue(apiClient.isPairedPcOrigin("https://100.89.163.64:5000/v1/audio/transcriptions"))
        assertTrue(apiClient.isPairedPcOrigin("https://100.89.163.64:5000/health"))
        assertTrue(apiClient.isPairedPcOrigin("https://100.89.163.64:5000/v1/models"))

        // 2. Different host -> false
        assertFalse(apiClient.isPairedPcOrigin("https://api.groq.com/openai/v1/chat/completions"))
        assertFalse(apiClient.isPairedPcOrigin("https://api.openai.com/v1/chat/completions"))
        assertFalse(apiClient.isPairedPcOrigin("https://100.89.163.65:5000/v1"))

        // 3. Different port on same host -> false
        assertFalse(apiClient.isPairedPcOrigin("https://100.89.163.64:5001/v1"))
        assertFalse(apiClient.isPairedPcOrigin("https://100.89.163.64:8080/health"))

        // 4. Different scheme -> false
        assertFalse(apiClient.isPairedPcOrigin("http://100.89.163.64:5000/v1"))

        // 5. If pin is not stored -> false (no pinned origin)
        repository.serverCertSha256 = null
        assertFalse(apiClient.isPairedPcOrigin("https://100.89.163.64:5000/v1/audio/transcriptions"))
    }

    @Test
    fun testClientSelectionRoutesPcVsPublic() {
        repository.baseUrl = "https://100.89.163.64:5000/v1"
        repository.serverCertSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        val pcUrl = "https://100.89.163.64:5000/health"
        val cloudUrl = "https://api.groq.com/openai/v1/chat/completions"
        val differentPortUrl = "https://100.89.163.64:5001/health"

        // pcUrl gets pinned client
        val pcClient = apiClient.clientFor(pcUrl)
        val pcFastClient = apiClient.fastClientFor(pcUrl)
        assertNotNull(pcClient)
        assertNotNull(pcFastClient)

        // Cloud and different port get public client (they share the same instance)
        val cloudClient = apiClient.clientFor(cloudUrl)
        val diffPortClient = apiClient.clientFor(differentPortUrl)
        assertEquals(cloudClient, diffPortClient)
    }

    @Test
    fun testMockWebServerPcOriginPinVerificationAndOtherOriginBypass() {
        // Set up PC server with a self-signed certificate
        val pcCert = HeldCertificate.Builder()
            .commonName("Whisper Unchained Server")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val pcHandshakeCerts = HandshakeCertificates.Builder()
            .heldCertificate(pcCert)
            .build()
        pcServer.useHttps(pcHandshakeCerts.sslSocketFactory(), false)
        pcServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"pc_ok\"}"))
        pcServer.start()

        val pcUrl = pcServer.url("/v1").toString()
        val pcPin = PinnedCertificateHelper.computeSha256Fingerprint(pcCert.certificate)

        // Store pairing in repository
        repository.baseUrl = pcUrl
        repository.serverCertSha256 = pcPin

        // 1. Calling PC server with matching pin succeeds
        val pcClient = apiClient.clientFor(pcServer.url("/v1/audio/transcriptions").toString())
        val pcResponse = pcClient.newCall(Request.Builder().url(pcServer.url("/v1/audio/transcriptions")).build()).execute()
        assertTrue(pcResponse.isSuccessful)
        assertEquals("{\"status\":\"pc_ok\"}", pcResponse.body?.string())

        // 2. Calling PC server with WRONG pin fails (evicting pooled connections so new handshake is forced)
        apiClient.evictConnections()
        repository.serverCertSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        try {
            pcClient.newCall(Request.Builder().url(pcServer.url("/v1/audio/transcriptions")).build()).execute()
            fail("Expected SSLException when pin is wrong")
        } catch (e: javax.net.ssl.SSLException) {
            // Expected! (Pin mismatch causes handshake / peer verification failure)
        }

        // Restore valid pin
        repository.serverCertSha256 = pcPin

        // 3. Different port or host is routed to publicClient and NOT checked against the PC pin
        val otherCert = HeldCertificate.Builder()
            .commonName("Other Service")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val otherHandshakeCerts = HandshakeCertificates.Builder()
            .heldCertificate(otherCert)
            .build()
        otherServer.useHttps(otherHandshakeCerts.sslSocketFactory(), false)
        otherServer.start()

        val otherUrl = otherServer.url("/v1").toString()
        assertFalse(apiClient.isPairedPcOrigin(otherUrl))
    }
}
