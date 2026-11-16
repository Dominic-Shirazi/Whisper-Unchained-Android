// This file tests OnDeviceModelManager manifest parsing, SHA-256 calculation, corrupt file pruning, and removed model migration.
// It is called by the unit test runner (`./gradlew testFullDebugUnitTest`).
// Inputs: models-manifest.json, temporary test files with known hashes.
// Outputs: Validates that models are accurately parsed, hashes match, corrupt files are purged, and v2 model is migrated per contracts/05 §2.2 & F-52 revised.

package com.example.whisperremote

// Reason for edit: Update tests for 3 models (unified, 110m, v3), verify v2 removal and migration per F-52 revised & F-57.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.util.ModelDownloadState
import com.example.whisperremote.util.ModelFileSpec
import com.example.whisperremote.util.OnDeviceModelManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
@Config(sdk = [34])
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

        // Models: unified (recommended), 110m (small), v3 (other languages)
        assertEquals(3, modelManager.availableModels.size)

        val recommendedModel = modelManager.availableModels[OnDeviceModelManager.RECOMMENDED_MODEL_ID]
        assertNotNull(recommendedModel)
        assertEquals("Recommended · most accurate (633 MB)", recommendedModel!!.displayName)
        assertEquals(4, recommendedModel.allFiles.size)
        assertEquals("parakeet-unified-en-0.6b-int8.encoder.int8.onnx", recommendedModel.encoder.fileName)
        assertEquals(654040552L, recommendedModel.encoder.expectedBytes)
        assertEquals("6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0", recommendedModel.encoder.sha256)
        assertEquals("NVIDIA Open Model License", recommendedModel.license)
        assertNotNull(recommendedModel.notice)

        val defaultModel = modelManager.availableModels[OnDeviceModelManager.DEFAULT_MODEL_ID]
        assertNotNull(defaultModel)
        assertEquals("Small and fast (135 MB)", defaultModel!!.displayName)
        assertEquals(4, defaultModel.allFiles.size)
        assertEquals("parakeet-tdt-110m-en-int8.encoder.int8.onnx", defaultModel.encoder.fileName)
        assertEquals(131113202L, defaultModel.encoder.expectedBytes)
        assertEquals("0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657", defaultModel.encoder.sha256)

        val multiModel = modelManager.availableModels[OnDeviceModelManager.MULTI_MODEL_ID]
        assertNotNull(multiModel)
        assertEquals("Other languages (671 MB)", multiModel!!.displayName)
        assertEquals("For English, the recommended model is more accurate.", multiModel.description)

        // v2 model must not be in availableModels
        assertNull(modelManager.availableModels[OnDeviceModelManager.REMOVED_V2_MODEL_ID])
    }

    @Test
    fun testMigrationOfRemovedV2Model() {
        val repo = SettingsRepository(context)
        val v2Dir = File(context.filesDir, "models/${OnDeviceModelManager.REMOVED_V2_MODEL_ID}")
        v2Dir.mkdirs()
        File(v2Dir, "dummy_model.onnx").writeText("dummy")
        assertTrue(v2Dir.exists())

        repo.selectedOnDeviceModelId = OnDeviceModelManager.REMOVED_V2_MODEL_ID

        val notice = modelManager.checkAndMigrateRemovedModels(repo)
        assertNotNull(notice)
        assertTrue(notice!!.contains("That model was replaced"))
        assertEquals(OnDeviceModelManager.DEFAULT_MODEL_ID, repo.selectedOnDeviceModelId)
        assertFalse("V2 directory must be purged on update", v2Dir.exists())
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
