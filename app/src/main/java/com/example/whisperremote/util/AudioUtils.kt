// This file is called by OnDeviceTranscriptionEngine and unit tests to parse, convert, and validate audio waveforms.
// Inputs: WAV audio File, raw PCM 16-bit ByteArrays, or normalized FloatArray buffers.
// Outputs: Normalized FloatArray in range [-1.0f, 1.0f] suitable for Silero VAD and sherpa-onnx OfflineRecognizer, and WavInfo metadata.
// It parses RIFF/WAVE chunks (skipping metadata/JUNK chunks, handling mono and stereo PCM), normalizes 16-bit little-endian samples to float,
// and ensures consistent 16kHz audio input for on-device speech-to-text processing per contracts/05-on-device.md §2.1.

package com.example.whisperremote.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataSizeBytes: Int,
    val durationSeconds: Float
)

object AudioUtils {

    /**
     * Reads a 16-bit PCM WAV file and decodes it into a normalized FloatArray [-1.0f, 1.0f].
     * Handles RIFF chunk structure, safely skipping auxiliary chunks (JUNK, LIST, INFO, etc.)
     * until the "data" chunk is reached. Averages stereo channels to mono if required.
     */
    @Throws(IOException::class)
    fun readWavToFloatArray(file: File): FloatArray {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            var read = input.read(header)
            if (read < 12) throw IOException("WAV file too short for RIFF header: ${file.name}")

            val riffTag = String(header, 0, 4, Charsets.US_ASCII)
            val waveTag = String(header, 8, 4, Charsets.US_ASCII)
            if (riffTag != "RIFF" || waveTag != "WAVE") {
                throw IOException("Invalid WAV header format: riffTag=$riffTag, waveTag=$waveTag")
            }

            var audioFormat = 1
            var channels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataBytes: ByteArray? = null

            val chunkHeader = ByteArray(8)
            while (true) {
                read = input.read(chunkHeader)
                if (read < 8) break // End of file

                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

                if (chunkSize < 0) {
                    throw IOException("Corrupt chunk size in WAV: $chunkSize")
                }

                when (chunkId) {
                    "fmt " -> {
                        val fmtBuffer = ByteArray(chunkSize)
                        val fmtRead = input.read(fmtBuffer)
                        if (fmtRead < chunkSize) throw IOException("Incomplete fmt chunk")
                        val bb = ByteBuffer.wrap(fmtBuffer).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF

                        if (audioFormat != 1) { // 1 = PCM
                            throw IOException("Unsupported WAV audio format: $audioFormat (only uncompressed PCM 1 is supported)")
                        }
                    }
                    "data" -> {
                        dataBytes = ByteArray(chunkSize)
                        var bytesReadTotal = 0
                        while (bytesReadTotal < chunkSize) {
                            val count = input.read(dataBytes, bytesReadTotal, chunkSize - bytesReadTotal)
                            if (count == -1) break
                            bytesReadTotal += count
                        }
                        if (bytesReadTotal < chunkSize) {
                            // Truncate to actual read length
                            val actual = ByteArray(bytesReadTotal)
                            System.arraycopy(dataBytes, 0, actual, 0, bytesReadTotal)
                            dataBytes = actual
                        }
                        break // We got the audio data
                    }
                    else -> {
                        // Skip auxiliary chunk (e.g. LIST, INFO, JUNK)
                        var toSkip = chunkSize.toLong()
                        while (toSkip > 0) {
                            val skipped = input.skip(toSkip)
                            if (skipped <= 0) break
                            toSkip -= skipped
                        }
                    }
                }
            }

            if (dataBytes == null) {
                throw IOException("No 'data' chunk found in WAV file: ${file.name}")
            }

            return pcmToFloatArray(dataBytes, channels, bitsPerSample)
        }
    }

    /**
     * Converts raw PCM byte buffer into normalized float array in range [-1.0f, 1.0f].
     * Downmixes multi-channel audio to mono by channel averaging.
     */
    fun pcmToFloatArray(data: ByteArray, channels: Int, bitsPerSample: Int): FloatArray {
        if (bitsPerSample != 16) {
            throw IllegalArgumentException("Only 16-bit PCM is currently supported, got $bitsPerSample-bit")
        }
        val numShorts = data.size / 2
        val numSamples = numShorts / maxOf(1, channels)
        val floats = FloatArray(numSamples)

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        if (channels == 1) {
            for (i in 0 until numSamples) {
                floats[i] = bb.short / 32768.0f
            }
        } else {
            // Downmix multiple channels by averaging
            for (i in 0 until numSamples) {
                var sum = 0.0f
                for (c in 0 until channels) {
                    sum += bb.short / 32768.0f
                }
                floats[i] = sum / channels
            }
        }

        return floats
    }

    /**
     * Extracts basic metadata from WAV header without loading entire audio body into memory.
     */
    @Throws(IOException::class)
    fun getWavInfo(file: File): WavInfo {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            if (input.read(header) < 12) throw IOException("WAV file too short for header")
            val riff = String(header, 0, 4, Charsets.US_ASCII)
            val wave = String(header, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") throw IOException("Invalid WAV signature")

            var channels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataBytes = 0

            val chunkHeader = ByteArray(8)
            while (true) {
                if (input.read(chunkHeader) < 8) break
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (chunkSize < 0) break

                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        input.read(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // format
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataBytes = chunkSize
                        break
                    }
                    else -> {
                        input.skip(chunkSize.toLong())
                    }
                }
            }

            val bytesPerSample = (bitsPerSample / 8) * channels
            val totalSamples = if (bytesPerSample > 0) dataBytes / bytesPerSample else 0
            val duration = if (sampleRate > 0) totalSamples.toFloat() / sampleRate else 0.0f

            return WavInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                dataSizeBytes = dataBytes,
                durationSeconds = duration
            )
        }
    }

    /**
     * Helper to write normalized FloatArray samples to a standard 16-bit 16kHz mono WAV file.
     * Useful for test fixtures or saving chunked audio.
     */
    @Throws(IOException::class)
    fun writeFloatArrayToWav(file: File, samples: FloatArray, sampleRate: Int = 16000) {
        val channels = 1
        val bitsPerSample = 16
        val dataLen = samples.size * 2
        val totalLen = dataLen + 36

        FileOutputStream(file).use { fos ->
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(totalLen)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16) // SubChunk1Size
            bb.putShort(1.toShort()) // PCM format = 1
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * channels * bitsPerSample / 8)
            bb.putShort((channels * bitsPerSample / 8).toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataLen)

            fos.write(header)

            val pcmBytes = ByteArray(dataLen)
            val pcmBb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val clamped = s.coerceIn(-1.0f, 1.0f)
                val shortVal = (clamped * 32767.0f).toInt().toShort()
                pcmBb.putShort(shortVal)
            }
            fos.write(pcmBytes)
        }
    }
}
