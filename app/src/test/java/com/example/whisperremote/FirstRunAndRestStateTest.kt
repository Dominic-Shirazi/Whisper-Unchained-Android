// This file tests first-run setup defaults, accessibility focused editable detection, and AI cleanup synchronization.
// It is called by the Gradle unit test runner during build verification.
// Inputs: SettingsRepository, DictationAccessibilityService, ApiClient health check responses.
// Outputs: Verifies that cleanup defaults mirror the server unless overridden by the user, and that accessibility helpers correctly identify focus state.
// Reason for creation: Unit tests for F-47 (returnToRest/hasFocusedEditable) and F-48 (cleanup sync & setup defaults).

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstRunAndRestStateTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        repository.cleanupUserSet = false
        repository.cleanupEnabled = false
        repository.providerPreset = ProviderPreset.SELF_HOSTED
    }

    @Test
    fun testSyncSmartCleanupDefault_mirrorsServerWhenUserNotSet() {
        // When user has not explicitly configured cleanup, it mirrors the server setting
        assertFalse(repository.cleanupUserSet)
        repository.syncSmartCleanupDefault(true)
        assertTrue(repository.cleanupEnabled)

        repository.syncSmartCleanupDefault(false)
        assertFalse(repository.cleanupEnabled)
    }

    @Test
    fun testSyncSmartCleanupDefault_preservesUserChoiceWhenUserSet() {
        // When user has explicitly set cleanup preference, server default never overrides it
        repository.cleanupUserSet = true
        repository.cleanupEnabled = false

        repository.syncSmartCleanupDefault(true)
        assertFalse(repository.cleanupEnabled)

        repository.cleanupEnabled = true
        repository.syncSmartCleanupDefault(false)
        assertTrue(repository.cleanupEnabled)
    }

    @Test
    fun testStartOnBoot_defaultsToTrue() {
        assertTrue(repository.startOnBoot)
    }

    @Test
    fun testDictationAccessibilityService_hasFocusedEditable_defaultFalse() {
        // Without active connected accessibility service, hasFocusedEditable returns false
        assertFalse(DictationAccessibilityService.hasFocusedEditable())
    }

    @Test
    fun testHealthCheck_syncsCleanupAlwaysAutomatically() {
        val server = MockWebServer()
        server.start()
        try {
            repository.baseUrl = server.url("/v1").toString()
            val apiClient = ApiClient(context)

            // Server responds with cleanup_always = true
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"status":"ok","model":"large-v3-turbo","cleanup_always":true}""")
            )

            assertFalse(repository.cleanupEnabled)
            assertFalse(repository.cleanupUserSet)

            val latch = CountDownLatch(1)
            var resultOk = false
            apiClient.checkHealth { health ->
                if (health is HealthResult.Ok) {
                    resultOk = true
                }
                latch.countDown()
            }

            val start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }

            assertTrue(resultOk)
            // Repository cleanupEnabled must now be true because health check synced it
            assertTrue(repository.cleanupEnabled)
        } finally {
            server.shutdown()
        }
    }
}
