// This file provides certificate fingerprint computation, pinned TLS verification, and SSLContext configuration.
// It is called by ApiClient when establishing HTTPS connections to Whisper Unchained Server or cloud endpoints.
// Inputs: Expected certificate SHA-256 fingerprint string (cert_sha256 from pairing QR payload v2), server X509Certificate chain.
// Outputs: Custom X509TrustManager, HostnameVerifier, and SSLSocketFactory enforcing leaf certificate SHA-256 pin matching per contract 06 §3.
// Conforms to contracts/06-https.md §3.

package com.example.whisperremote.net

import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object PinnedCertificateHelper {

    private const val TAG = "PinnedCertHelper"

    /**
     * Computes the lowercase hex SHA-256 fingerprint of an X509Certificate's DER bytes.
     */
    fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Gets the system default X509TrustManager.
     */
    fun getDefaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Creates an X509TrustManager that validates server leaf certificates against a static pin.
     */
    fun createTrustManager(expectedPin: String?): X509TrustManager {
        return createDynamicTrustManager { expectedPin }
    }

    /**
     * Creates an X509TrustManager that dynamically evaluates the expected pin on each handshake.
     * When pin is null or blank, falls back to normal CA validation.
     */
    fun createDynamicTrustManager(pinProvider: () -> String?): X509TrustManager {
        val defaultTm = getDefaultTrustManager()

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTm.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cleanPin = pinProvider()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                if (cleanPin == null) {
                    // Normal CA validation for cloud providers or standard HTTPS
                    defaultTm.checkServerTrusted(chain, authType)
                    return
                }

                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server presented no certificate chain.")
                }

                // Verify the leaf certificate (chain[0]) against the stored pin per contract 06 §3.1
                val leaf = chain[0]
                val leafFingerprint = computeSha256Fingerprint(leaf)
                if (!leafFingerprint.equals(cleanPin, ignoreCase = true)) {
                    Log.e(TAG, "Pin mismatch! Expected: $cleanPin, got: $leafFingerprint")
                    throw CertificateException("Your PC's security certificate changed. If you reinstalled Whisper Unchained Server, scan its pairing code again.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val cleanPin = pinProvider()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                return if (cleanPin == null) defaultTm.acceptedIssuers else arrayOf()
            }
        }
    }

    /**
     * Creates a HostnameVerifier where identity is anchored to a static expectedPin.
     */
    fun createHostnameVerifier(expectedPin: String?): HostnameVerifier {
        return createDynamicHostnameVerifier { expectedPin }
    }

    /**
     * Creates a HostnameVerifier where identity is anchored dynamically to pinProvider.
     * If pin is matched, the certificate pin is the identity (accepts any hostname/IP per contract 06 §3.1).
     * Otherwise delegates to the system default verifier.
     */
    fun createDynamicHostnameVerifier(pinProvider: () -> String?): HostnameVerifier {
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        return HostnameVerifier { hostname, session ->
            val cleanPin = pinProvider()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            if (cleanPin != null) {
                try {
                    val peerCerts = session.peerCertificates
                    if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                        val leaf = peerCerts[0] as X509Certificate
                        val leafFingerprint = computeSha256Fingerprint(leaf)
                        if (leafFingerprint.equals(cleanPin, ignoreCase = true)) {
                            return@HostnameVerifier true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting peer certificate for hostname verification: ${e.message}")
                }
            }
            defaultVerifier.verify(hostname, session)
        }
    }

    /**
     * Creates an SSLSocketFactory using the pinned trust manager.
     */
    fun createSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * Applies dynamic pinned TLS configuration to an OkHttpClient.Builder.
     */
    fun configureClient(builder: OkHttpClient.Builder, pinProvider: () -> String?): OkHttpClient.Builder {
        val trustManager = createDynamicTrustManager(pinProvider)
        val socketFactory = createSocketFactory(trustManager)
        val hostnameVerifier = createDynamicHostnameVerifier(pinProvider)

        return builder
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
    }

    /**
     * Applies static pinned TLS configuration to an OkHttpClient.Builder.
     */
    fun configureClient(builder: OkHttpClient.Builder, expectedPin: String?): OkHttpClient.Builder {
        return configureClient(builder) { expectedPin }
    }
}
