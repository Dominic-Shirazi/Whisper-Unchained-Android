// This file tests OnDeviceModelManager manifest parsing, SHA-256 calculation, and corrupt file pruning.
// It is called by the unit test runner (`./gradlew testFullDebugUnitTest`).
// Inputs: models-manifest.json, temporary test files with known hashes.
// Outputs: Validates that models are accurately parsed, hashes match, and corrupt files are purged per contracts/05 §2.2.
// Reason for creation: Unit test suite for OnDeviceModelManager in v1.3 Part 2 per F-28.
// Reason for edit: Update expected defaultModel.displayName to "Standard (English) · 136 MB" per F-32.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.util.ModelDownloadState
import com.example.whisperremote.util.ModelFileSpec
import com.example.whisperremote.util.OnDeviceModelManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnDeviceModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var modelManager: OnDeviceModelManager
    private lateinit var manifestJson: String

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val candidates = listOf(
            File("src/main/assets/models-manifest.json"),
            File("app/src/main/assets/models-manifest.json"),
            File("../app/src/main/assets/models-manifest.json")
        )
        val file = candidates.find { it.exists() }
            ?: throw IllegalStateException("models-manifest.json not found from ${File(".").absolutePath}")
        manifestJson = file.readText()
        modelManager = OnDeviceModelManager(context, manifestJson)
    }

    @Test
    fun testManifestParsing() {
        // VAD
        assertEquals("silero_vad.onnx", modelManager.vadSpec.fileName)
        assertEquals(643854L, modelManager.vadSpec.expectedBytes)
        assertEquals("9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", modelManager.vadSpec.sha256)

        // Models
        assertEquals(3, modelManager.availableModels.size)

        val defaultModel = modelManager.availableModels[OnDeviceModelManager.DEFAULT_MODEL_ID]
        assertNotNull(defaultModel)
        assertEquals("Standard (English) · 136 MB", defaultModel!!.displayName)
        assertEquals(4, defaultModel.allFiles.size)
        assertEquals("parakeet-tdt-110m-en-int8.encoder.int8.onnx", defaultModel.encoder.fileName)
        assertEquals(131113202L, defaultModel.encoder.expectedBytes)
        assertEquals("0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657", defaultModel.encoder.sha256)
    }

    @Test
    fun testSha256Calculation() {
        val testFile = tempFolder.newFile("hello.txt")
        testFile.writeText("Whisper Unchained")

        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest("Whisper Unchained".toByteArray())
            .joinToString("") { "%02x".format(it) }

        val actualSha256 = OnDeviceModelManager.computeSha256(testFile)
        assertEquals(expectedSha256, actualSha256)
    }

    @Test
    fun testVerifyAndPruneFile_validFilePreserved() {
        val testFile = tempFolder.newFile("sample.bin")
        val content = "1234567890"
        testFile.writeText(content)

        val hash = OnDeviceModelManager.computeSha256(testFile)
        val spec = ModelFileSpec("test", "sample.bin", testFile.length(), hash)

        val result = modelManager.verifyAndPruneFile(testFile, spec)
        assertTrue(result)
        assertTrue(testFile.exists())
    }

    @Test
    fun testVerifyAndPruneFile_corruptHashDeleted() {
        val testFile = tempFolder.newFile("corrupt_hash.bin")
        testFile.writeText("Corrupted data")

        val spec = ModelFileSpec("test", "corrupt_hash.bin", testFile.length(), "0000000000000000000000000000000000000000000000000000000000000000")

        val result = modelManager.verifyAndPruneFile(testFile, spec)
        assertFalse(result)
        assertFalse("Corrupted file must be deleted on hash mismatch per contract 05 §2.2", testFile.exists())
    }

    @Test
    fun testVerifyAndPruneFile_wrongSizeDeleted() {
        val testFile = tempFolder.newFile("corrupt_size.bin")
        testFile.writeText("Partial download")

        val spec = ModelFileSpec("test", "corrupt_size.bin", 999999L, "somehash")

        val result = modelManager.verifyAndPruneFile(testFile, spec)
        assertFalse(result)
        assertFalse("Partial/wrong size file must be deleted per contract 05 §2.2", testFile.exists())
    }

    @Test
    fun testIsModelReady_falseWhenMissing() {
        assertFalse(modelManager.isModelReady(OnDeviceModelManager.DEFAULT_MODEL_ID))
    }

    @Test
    fun testResumableDownload_withMockWebServer206() {
        val server = MockWebServer()
        server.start()

        try {
            val fullContent = "Whisper Unchained Resumable Download Test Buffer Content"
            val totalBytes = fullContent.toByteArray(Charsets.UTF_8).size
            val halfBytes = totalBytes / 2
            val part1 = fullContent.substring(0, halfBytes)
            val part2 = fullContent.substring(halfBytes)

            val fullHash = MessageDigest.getInstance("SHA-256")
                .digest(fullContent.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val customManifest = """
            {
             "version": 1,
             "models": {
              "parakeet-tdt-110m-en-int8": {
               "encoder": { "file": "enc.bin", "bytes": $totalBytes, "sha256": "$fullHash" },
               "decoder": { "file": "dec.bin", "bytes": $totalBytes, "sha256": "$fullHash" },
               "joiner": { "file": "jnr.bin", "bytes": $totalBytes, "sha256": "$fullHash" },
               "tokens": { "file": "tok.txt", "bytes": $totalBytes, "sha256": "$fullHash" }
              }
             },
             "vad": { "file": "vad.bin", "bytes": $totalBytes, "sha256": "$fullHash" }
            }
            """.trimIndent()

            val manager = OnDeviceModelManager(context, customManifest, server.url("/").toString())

            // Seed an existing .part file for VAD with half the bytes
            val vadDir = manager.getVadDir()
            val partFile = File(vadDir, "vad.bin.part")
            partFile.writeText(part1)

            // VAD request: server returns 206 Partial Content with part2
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $halfBytes-${totalBytes - 1}/$totalBytes")
                    .setBody(part2)
            )
            // Model files (enc, dec, jnr, tok): return 200 OK with full content
            for (i in 1..4) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(fullContent)
                )
            }

            var downloadSuccess = false
            manager.downloadModel("parakeet-tdt-110m-en-int8", allowMobileData = true) { state ->
                if (state is ModelDownloadState.Ready) {
                    downloadSuccess = true
                }
            }

            val recordedRequest = server.takeRequest()
            assertEquals("bytes=$halfBytes-", recordedRequest.getHeader("Range"))
            assertTrue(downloadSuccess)

            val finalVad = manager.getVadFile()
            assertTrue(finalVad.exists())
            assertEquals(totalBytes.toLong(), finalVad.length())
            assertEquals(fullContent, finalVad.readText())
            assertTrue(manager.isModelReady("parakeet-tdt-110m-en-int8"))
        } finally {
            server.shutdown()
        }
    }
}
