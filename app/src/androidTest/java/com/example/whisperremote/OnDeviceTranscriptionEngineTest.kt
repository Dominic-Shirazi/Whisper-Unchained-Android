// This file contains on-device instrumented tests for OnDeviceTranscriptionEngine.
// It verifies sherpa-onnx OfflineRecognizer and Silero VAD end-to-end on real hardware.
// Inputs: WAV audio files from /data/local/tmp/asr/phonewav, Parakeet TDT 110M int8 model weights, Silero VAD.
// Outputs: Validated transcribed speech, name corrections, and filler removal per contracts/05-on-device.md §2.

package com.example.whisperremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.util.OnDeviceModelManager
import com.example.whisperremote.util.OnDeviceTranscriptionEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnDeviceTranscriptionEngineTest {

    private lateinit var context: android.content.Context
    private lateinit var modelManager: OnDeviceModelManager
    private lateinit var repository: SettingsRepository
    private lateinit var engine: OnDeviceTranscriptionEngine
    private lateinit var testWavFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        modelManager = OnDeviceModelManager(context)
        repository = SettingsRepository(context)

        // Stage model files from /data/local/tmp/asr into app storage if needed
        stageModelsFromDeviceTmp()

        engine = OnDeviceTranscriptionEngine(context, modelManager, repository)
    }

    @After
    fun tearDown() {
        engine.release()
    }

    private fun stageModelsFromDeviceTmp() {
        val srcDir = File("/data/local/tmp/asr/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8")
        val targetModelDir = modelManager.getModelDir(OnDeviceModelManager.DEFAULT_MODEL_ID)
        val spec = modelManager.availableModels[OnDeviceModelManager.DEFAULT_MODEL_ID]!!

        val fileMap = mapOf(
            File(srcDir, "encoder.int8.onnx") to File(targetModelDir, spec.encoder.fileName),
            File(srcDir, "decoder.int8.onnx") to File(targetModelDir, spec.decoder.fileName),
            File(srcDir, "joiner.int8.onnx") to File(targetModelDir, spec.joiner.fileName),
            File(srcDir, "tokens.txt") to File(targetModelDir, spec.tokens.fileName)
        )

        for ((src, dst) in fileMap) {
            if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
                src.copyTo(dst, overwrite = true)
            }
        }

        // VAD
        val srcVad = File("/data/local/tmp/asr/silero_vad.onnx")
        val dstVad = modelManager.getVadFile()
        if (srcVad.exists() && (!dstVad.exists() || dstVad.length() != srcVad.length())) {
            srcVad.copyTo(dstVad, overwrite = true)
        }

        // Test WAV: c0_2s.wav
        val srcWav = File("/data/local/tmp/asr/phonewav/c0_2s.wav")
        testWavFile = File(context.cacheDir, "test_c0.wav")
        if (srcWav.exists()) {
            srcWav.copyTo(testWavFile, overwrite = true)
        }
    }

    @Test
    fun testInitializationAndTranscriptionEndToEnd() {
        assertTrue("Test WAV file must exist", testWavFile.exists())
        assertTrue("Model must be ready on disk", modelManager.isModelReady(OnDeviceModelManager.DEFAULT_MODEL_ID))

        val initialized = engine.initialize(OnDeviceModelManager.DEFAULT_MODEL_ID)
        assertTrue("Engine must initialize successfully", initialized)
        assertTrue("isInitialized flag must be true", engine.isInitialized)

        // Clear local name corrections for initial test
        repository.localNameCorrections = ""

        val transcript = engine.transcribe(testWavFile, removeFillers = false)
        assertTrue("Transcript should not be empty", transcript.isNotEmpty())
        assertTrue("Transcript should contain 'California': got '$transcript'", transcript.contains("California", ignoreCase = true))

        engine.release()
        assertFalse("Engine should be uninitialized after release", engine.isInitialized)
    }

    @Test
    fun testTranscriptionWithNameCorrections() {
        engine.initialize(OnDeviceModelManager.DEFAULT_MODEL_ID)

        // Set name correction: California -> New York
        repository.localNameCorrections = "California: New York"

        val transcript = engine.transcribe(testWavFile, removeFillers = false)
        assertTrue("Transcript should contain 'New York': got '$transcript'", transcript.contains("New York"))
        assertFalse("Transcript should no longer contain 'California'", transcript.contains("California", ignoreCase = true))

        // Reset
        repository.localNameCorrections = ""
    }

    @Test
    fun testHotwordsBeamSearchDecoding() {
        // Populate vocabulary to trigger modified_beam_search
        repository.cachedServerVocabulary = setOf("California", "Dominic", "TDT")

        val initialized = engine.initialize(OnDeviceModelManager.DEFAULT_MODEL_ID)
        assertTrue("Engine must initialize with hotwords", initialized)

        val transcript = engine.transcribe(testWavFile, removeFillers = false)
        assertTrue("Transcript should contain 'California'", transcript.contains("California", ignoreCase = true))

        // Reset
        repository.cachedServerVocabulary = emptySet()
    }
}
