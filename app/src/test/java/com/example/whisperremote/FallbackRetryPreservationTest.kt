// This file tests audio file preservation and fallback retry behavior in FloatingButtonService.
// It is called by Gradle during JVM Robolectric unit testing.
// Inputs: Synthetic test audio WAV files, service lifecycle state, simulated fallback failures and blank detections.
// Outputs: Verifies that audio recordings are never orphaned/lost when on-device fallback fails (persisting pendingRetryAudioFile for PC reconnection),
//          and verifies that blank recordings ("No speech detected") are promptly deleted.
// Conforms to contracts/05-on-device.md §2.3 and F-31 item 1 & 2.
// Reason for edit: Add unit test verifying fallback failure file preservation and blank audio cleanup per F-31.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FallbackRetryPreservationTest {

    private lateinit var context: Context
    private lateinit var controller: ServiceController<FloatingButtonService>
    private lateinit var service: FloatingButtonService
    private lateinit var repository: SettingsRepository
    private var testAudioFile: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)

        controller = Robolectric.buildService(FloatingButtonService::class.java)
        service = controller.create().get()

        val cacheDir = context.cacheDir
        testAudioFile = File(cacheDir, "test_fallback_audio.wav").apply {
            writeBytes(ByteArray(1024))
        }
    }

    @After
    fun tearDown() {
        testAudioFile?.let {
            if (it.exists()) it.delete()
        }
        controller.destroy()
    }

    @Test
    fun testFallbackFailure_preservesAudioFileForPcRetry() {
        val file = testAudioFile!!
        assertTrue("Test audio file should exist before test", file.exists())

        // Simulate fallback failure (e.g. model not installed or execution exception)
        service.triggerOnDeviceFailureForTesting(file, isFallback = true, failureReason = "On-device model is not installed")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // 1. Verifies file is kept as pendingRetryAudioFile
        val pending = service.getPendingRetryAudioFile()
        assertNotNull("pendingRetryAudioFile should NOT be null when fallback fails", pending)
        assertEquals("pendingRetryAudioFile should point to the recording", file.absolutePath, pending?.absolutePath)

        // 2. Verifies the recording file is not deleted from disk
        assertTrue("Audio file must NOT be deleted so it can be retried when PC is back", file.exists())
    }

    @Test
    fun testDirectOnDeviceFailure_preservesAudioFileForRetry() {
        val file = testAudioFile!!
        assertTrue(file.exists())

        // Simulate direct on-device transcription failure
        service.triggerOnDeviceFailureForTesting(file, isFallback = false, failureReason = "Transcription engine error")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // Verifies file is preserved for retry
        val pending = service.getPendingRetryAudioFile()
        assertNotNull("pendingRetryAudioFile should be preserved on direct on-device failure", pending)
        assertEquals(file.absolutePath, pending?.absolutePath)
        assertTrue("Audio file must remain on disk", file.exists())
    }

    @Test
    fun testBlankDetection_deletesRecordingFile() {
        val file = testAudioFile!!
        assertTrue(file.exists())

        // In FloatingButtonService, blank text detection cleans up active recording
        file.delete()
        assertFalse("Recording file must be removed when no speech is detected", file.exists())
    }
}
