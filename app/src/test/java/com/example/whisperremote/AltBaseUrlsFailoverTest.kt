// This file tests alt_base_urls failover ordering, origin pin scoping, and Home Wi-Fi warning rules.
// It is called by Gradle test runners.
// Inputs: SettingsRepository configured with primary and alternate PC URLs, and Tailscale addresses.
// Outputs: Verifies failover priority order, cert pin origin matching across alternate addresses, and Wi-Fi warning suppression.
// Conforms to contracts/01-transcription-api.md §7, contract 06 §3.6, and F-55.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.net.TailscaleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AltBaseUrlsFailoverTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
    }

    @Test
    fun testFailoverOrderDefault() {
        // Without last working URL set: primary baseUrl first, then altBaseUrls in order
        repository.baseUrl = "https://192.168.1.50:8000"
        repository.altBaseUrls = listOf("https://100.100.1.2:8000", "https://pc.local:8000")
        repository.lastWorkingBaseUrl = ""

        val allUrls = repository.getAllPcBaseUrls()
        assertEquals(3, allUrls.size)
        assertEquals("https://192.168.1.50:8000", allUrls[0])
        assertEquals("https://100.100.1.2:8000", allUrls[1])
        assertEquals("https://pc.local:8000", allUrls[2])
    }

    @Test
    fun testFailoverOrderWithLastWorkingUrl() {
        // When an alternate address responded last, it moves to the front of the failover list
        repository.baseUrl = "https://192.168.1.50:8000"
        repository.altBaseUrls = listOf("https://100.100.1.2:8000", "https://pc.local:8000")
        repository.lastWorkingBaseUrl = "https://100.100.1.2:8000"

        val allUrls = repository.getAllPcBaseUrls()
        assertEquals(3, allUrls.size)
        assertEquals("https://100.100.1.2:8000", allUrls[0])
        assertEquals("https://192.168.1.50:8000", allUrls[1])
        assertEquals("https://pc.local:8000", allUrls[2])
    }

    @Test
    fun testPinAppliesToAlternateAddress() {
        // ApiClient.isPairedPcOrigin must match any address in getAllPcBaseUrls
        repository.baseUrl = "https://192.168.1.50:8000"
        repository.altBaseUrls = listOf("https://100.100.1.2:8000")
        repository.serverCertSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val apiClient = ApiClient(context)

        assertTrue(apiClient.isPairedPcOrigin("https://192.168.1.50:8000/v1/health"))
        assertTrue(apiClient.isPairedPcOrigin("https://100.100.1.2:8000/v1/audio/transcriptions"))
        assertFalse(apiClient.isPairedPcOrigin("https://api.groq.com/openai/v1/audio/transcriptions"))
        assertFalse(apiClient.isPairedPcOrigin("https://192.168.1.99:8000/v1/health"))
    }

    @Test
    fun testHomeWifiWarningRule() {
        // Warning should only be shown if NONE of the stored addresses is a Tailscale address

        // Case 1: Only home Wi-Fi address stored -> warning SHOULD show
        repository.baseUrl = "https://192.168.1.50:8000"
        repository.altBaseUrls = listOf("https://pc.home:8000")
        var hasTailscale = repository.getAllPcBaseUrls().any { TailscaleManager.isTailscaleAddress(it) }
        assertFalse(hasTailscale) // None is tailscale -> show warning

        // Case 2: Primary is home, alt is Tailscale (100.64.0.0/10) -> warning should NOT show
        repository.baseUrl = "https://192.168.1.50:8000"
        repository.altBaseUrls = listOf("https://100.115.92.1:8000")
        hasTailscale = repository.getAllPcBaseUrls().any { TailscaleManager.isTailscaleAddress(it) }
        assertTrue(hasTailscale) // Stored Tailscale address exists -> warning suppressed

        // Case 3: Primary is Tailscale magic DNS (*.ts.net) -> warning should NOT show
        repository.baseUrl = "https://mypc.tailscale.ts.net:8000"
        repository.altBaseUrls = emptyList()
        hasTailscale = repository.getAllPcBaseUrls().any { TailscaleManager.isTailscaleAddress(it) }
        assertTrue(hasTailscale) // Magic DNS is Tailscale -> warning suppressed
    }
}
