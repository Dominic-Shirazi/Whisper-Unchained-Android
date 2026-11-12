// This file tests PinnedCertificateHelper against MockWebServer and self-signed certificates using okhttp-tls HeldCertificate.
// Inputs: okhttp3.tls.HeldCertificate generating dynamic EC/RSA test certs, MockWebServer in HTTPS mode.
// Outputs: Asserts pinned leaf certificate SHA-256 fingerprint matching succeeds, pin mismatch rejects with CertificateException,
//          and HostnameVerifier correctly treats the verified pin as the host identity per contract 06 §3.

package com.example.whisperremote.net

import okhttp3.OkHttpClient
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
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class PinnedCertificateHelperTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFingerprintComputation() {
        val cert = HeldCertificate.Builder()
            .commonName("Whisper Unchained Server")
            .build()

        val fp = PinnedCertificateHelper.computeSha256Fingerprint(cert.certificate)
        assertNotNull(fp)
        assertEquals(64, fp.length) // 256 bits = 64 hex characters
        assertTrue(fp.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun testTrustManagerMatchingPinSucceeds() {
        val cert = HeldCertificate.Builder()
            .commonName("Whisper Unchained Server")
            .build()

        val pin = PinnedCertificateHelper.computeSha256Fingerprint(cert.certificate)
        val tm = PinnedCertificateHelper.createTrustManager(pin)

        // Should succeed without exception
        tm.checkServerTrusted(arrayOf(cert.certificate), "ECDHE_ECDSA")
    }

    @Test
    fun testTrustManagerWrongPinThrowsCertificateException() {
        val cert = HeldCertificate.Builder()
            .commonName("Whisper Unchained Server")
            .build()

        val wrongPin = "0000000000000000000000000000000000000000000000000000000000000000"
        val tm = PinnedCertificateHelper.createTrustManager(wrongPin)

        try {
            tm.checkServerTrusted(arrayOf(cert.certificate), "ECDHE_ECDSA")
            fail("Expected CertificateException on pin mismatch")
        } catch (e: CertificateException) {
            assertTrue(e.message?.contains("certificate changed") == true)
        }
    }

    @Test
    fun testHttpsRequestWithMatchingPinSucceedsOverMockWebServer() {
        val serverCert = HeldCertificate.Builder()
            .commonName("Whisper Unchained Server")
            .addSubjectAlternativeName("127.0.0.1")
            .build()

        val serverHandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        server.useHttps(serverHandshakeCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.start()

        val pin = PinnedCertificateHelper.computeSha256Fingerprint(serverCert.certificate)

        val client = PinnedCertificateHelper.configureClient(
            OkHttpClient.Builder(),
            expectedPin = pin
        ).build()

        val request = Request.Builder()
            .url(server.url("/health"))
            .build()

        val response = client.newCall(request).execute()
        assertTrue(response.isSuccessful)
        assertEquals("{\"status\":\"ok\"}", response.body?.string())
    }

    @Test
    fun testHttpsRequestWithWrongPinFailsOverMockWebServer() {
        val serverCert = HeldCertificate.Builder()
            .commonName("Whisper Unchained Server")
            .addSubjectAlternativeName("127.0.0.1")
            .build()

        val serverHandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        server.useHttps(serverHandshakeCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.start()

        val wrongPin = "1111111111111111111111111111111111111111111111111111111111111111"

        val client = PinnedCertificateHelper.configureClient(
            OkHttpClient.Builder(),
            expectedPin = wrongPin
        ).build()

        val request = Request.Builder()
            .url(server.url("/health"))
            .build()

        try {
            client.newCall(request).execute()
            fail("Expected SSLHandshakeException when pin does not match")
        } catch (e: SSLHandshakeException) {
            // Expected!
            assertTrue(e.cause is CertificateException || e.message?.contains("certificate") == true)
        }
    }
}
