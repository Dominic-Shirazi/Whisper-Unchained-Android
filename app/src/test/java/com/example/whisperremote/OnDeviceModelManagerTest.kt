// This file tests OnDeviceModelManager manifest parsing, SHA-256 calculation, and corrupt file pruning.
// It is called by the unit test runner (`./gradlew testFullDebugUnitTest`).
// Inputs: models-manifest.json, temporary test files with known hashes.
// Outputs: Validates that models are accurately parsed, hashes match, and corrupt files are purged per contracts/05 §2.2.
// Reason for creation: Unit test suite for OnDeviceModelManager in v1.3 Part 2 per F-28.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.util.ModelFileSpec
import com.example.whisperremote.util.OnDeviceModelManager
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
        assertEquals("Parakeet TDT 110M (Default)", defaultModel!!.displayName)
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
}
