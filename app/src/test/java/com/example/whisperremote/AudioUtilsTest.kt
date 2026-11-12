// This file contains unit tests for AudioUtils.
// It tests WAV parsing, float normalization, stereo downmixing, chunk skipping, and error handling.
// Strict verification for on-device audio conversion per contracts/05-on-device.md §2.1.

package com.example.whisperremote

import com.example.whisperremote.util.AudioUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class AudioUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRoundTripWavWriteAndRead() {
        val sampleRate = 16000
        val durationSec = 1.0f
        val numSamples = (sampleRate * durationSec).toInt()
        val originalFloats = FloatArray(numSamples) { i ->
            // 440 Hz sine wave
            sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat() * 0.8f
        }

        val testFile = File(tempFolder.root, "sine.wav")
        AudioUtils.writeFloatArrayToWav(testFile, originalFloats, sampleRate)

        val readFloats = AudioUtils.readWavToFloatArray(testFile)
        assertEquals("Sample count must match", originalFloats.size, readFloats.size)

        var maxDiff = 0.0f
        for (i in originalFloats.indices) {
            val diff = abs(originalFloats[i] - readFloats[i])
            if (diff > maxDiff) maxDiff = diff
        }

        // 16-bit PCM quantization step is 1/32768 ≈ 0.0000305
        assertTrue("Max quantization difference $maxDiff should be < 0.0001", maxDiff < 0.0001f)

        val info = AudioUtils.getWavInfo(testFile)
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(numSamples * 2, info.dataSizeBytes)
        assertEquals(1.0f, info.durationSeconds, 0.01f)
    }

    @Test
    fun testWavWithJunkChunkBeforeData() {
        val testFile = File(tempFolder.root, "with_junk.wav")
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val audioData = byteArrayOf(0x00, 0x00, 0x00, 0x40) // 2 samples: 0, 16384 (approx 0.5f)

        val junkData = "Some arbitrary junk metadata padding...".toByteArray(Charsets.US_ASCII)
        val junkChunkLen = junkData.size

        val totalDataLen = 36 + 8 + junkChunkLen + audioData.size

        FileOutputStream(testFile).use { fos ->
            val bb = ByteBuffer.allocate(44 + 8 + junkChunkLen).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(totalDataLen)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt chunk
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16)
            bb.putShort(1.toShort()) // PCM
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * channels * bitsPerSample / 8)
            bb.putShort((channels * bitsPerSample / 8).toShort())
            bb.putShort(bitsPerSample.toShort())

            // JUNK chunk
            bb.put("JUNK".toByteArray(Charsets.US_ASCII))
            bb.putInt(junkChunkLen)
            bb.put(junkData)

            // data chunk header
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(audioData.size)

            fos.write(bb.array())
            fos.write(audioData)
        }

        val floats = AudioUtils.readWavToFloatArray(testFile)
        assertEquals(2, floats.size)
        assertEquals(0.0f, floats[0], 0.001f)
        assertEquals(0.5f, floats[1], 0.001f)
    }

    @Test
    fun testStereoDownmixToMono() {
        // 2 samples stereo: Left=1.0f (32767), Right=0.0f (0) -> Average = 0.5f
        val stereoBytes = ByteArray(8)
        val bb = ByteBuffer.wrap(stereoBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(32767.toShort()) // L1
        bb.putShort(0.toShort())     // R1
        bb.putShort((-32768).toShort()) // L2
        bb.putShort(0.toShort())     // R2

        val monoFloats = AudioUtils.pcmToFloatArray(stereoBytes, channels = 2, bitsPerSample = 16)
        assertEquals(2, monoFloats.size)
        assertEquals(0.5f, monoFloats[0], 0.001f)
        assertEquals(-0.5f, monoFloats[1], 0.001f)
    }

    @Test
    fun testCorruptHeaderThrowsException() {
        val testFile = File(tempFolder.root, "corrupt.wav")
        testFile.writeText("NOT_A_WAV_FILE_HEADER")

        try {
            AudioUtils.readWavToFloatArray(testFile)
            fail("Expected IOException for invalid header")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Invalid WAV") == true || e.message?.contains("header") == true)
        }
    }

    @Test
    fun testRealWavFileParsing() {
        val realFile = File("build/c0_2s.wav")
        if (realFile.exists()) {
            val floats = AudioUtils.readWavToFloatArray(realFile)
            println("Real WAV samples count: ${floats.size}")
            assertTrue(floats.isNotEmpty())
            assertEquals(39154, floats.size)
        }
    }
}
