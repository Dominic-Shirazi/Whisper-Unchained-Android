// This file is called by FloatingButtonService, IntroScreen, and Settings UI to perform local offline speech transcription.
// Inputs: WAV audio File or FloatArray waveform, model selection ID, vocabulary hotwords, and post-processing preference flags.
// Outputs: Transcribed text string with name corrections applied and optional filler sound removal.
// It manages sherpa-onnx OfflineRecognizer (NeMo transducer int8) and Silero VAD (cutting speech into <= 25s pieces),
// dynamic hotword file compilation, and graceful fallback to greedy search if beam search hotwords fail per contracts/05-on-device.md §2.

package com.example.whisperremote.util

import android.content.Context
import android.util.Log
import com.example.whisperremote.data.SettingsRepository
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class OnDeviceTranscriptionEngine(
    private val context: Context,
    private val modelManager: OnDeviceModelManager = OnDeviceModelManager(context),
    private val settingsRepository: SettingsRepository = SettingsRepository(context)
) {
    companion object {
        private const val TAG = "OnDeviceTranscriptionEngine"
        private const val MAX_CHUNK_SAMPLES = 16000 * 25 // 25 seconds at 16kHz
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var currentModelId: String? = null
    private val lock = Any()

    val isInitialized: Boolean
        get() = synchronized(lock) { recognizer != null && vad != null }

    val loadedModelId: String?
        get() = synchronized(lock) { currentModelId }

    /**
     * Initializes the recognizer and Silero VAD with the specified model.
     * Generates a hotword file from server and local vocabulary/name corrections.
     * Falls back to greedy decoding if hotwords cannot be loaded.
     *
     * @return true if initialization succeeded, false if model files are missing or corrupted.
     */
    fun initialize(modelId: String = OnDeviceModelManager.DEFAULT_MODEL_ID): Boolean = synchronized(lock) {
        if (recognizer != null && currentModelId == modelId) {
            return true
        }

        release()

        if (!modelManager.isModelReady(modelId)) {
            Log.w(TAG, "Model $modelId or VAD is not ready on disk")
            return false
        }

        val vadFile = modelManager.getVadFile()
        val modelFiles = modelManager.getModelFiles(modelId) ?: return false

        val encoderFile = modelFiles["encoder"] ?: return false
        val decoderFile = modelFiles["decoder"] ?: return false
        val joinerFile = modelFiles["joiner"] ?: return false
        val tokensFile = modelFiles["tokens"] ?: return false

        try {
            // 1. Initialize Silero VAD (bounds dictation chunks to <= 25 seconds per contract 05 §2.2)
            val sileroConfig = SileroVadModelConfig(
                model = vadFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 25.0f
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu"
            )
            vad = Vad(null, vadConfig)

            // 2. Prepare hotwords file from vocabulary and name corrections per contract 05 §2.5
            val hotwordsFile = prepareHotwordsFile(modelId, tokensFile)

            // 3. Configure sherpa-onnx OfflineRecognizer
            val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
            val transducerConfig = OfflineTransducerModelConfig(
                encoder = encoderFile.absolutePath,
                decoder = decoderFile.absolutePath,
                joiner = joinerFile.absolutePath
            )
            val modelConfig = OfflineModelConfig(
                transducer = transducerConfig,
                tokens = tokensFile.absolutePath,
                bpeVocab = tokensFile.absolutePath,
                modelingUnit = "bpe",
                modelType = "nemo_transducer",
                numThreads = 4,
                provider = "cpu"
            )

            // Attempt beam search with hotwords first; fall back to greedy if beam fails
            var createdRecognizer: OfflineRecognizer? = null
            if (hotwordsFile != null && hotwordsFile.exists() && hotwordsFile.length() > 0) {
                try {
                    val beamConfig = OfflineRecognizerConfig(
                        featConfig = featConfig,
                        modelConfig = modelConfig,
                        decodingMethod = "modified_beam_search",
                        hotwordsFile = hotwordsFile.absolutePath,
                        hotwordsScore = 2.0f,
                        maxActivePaths = 4
                    )
                    createdRecognizer = OfflineRecognizer(null, beamConfig)
                    Log.i(TAG, "OfflineRecognizer loaded with modified_beam_search + hotwords")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load recognizer with hotwords, falling back to greedy search", e)
                }
            }

            if (createdRecognizer == null) {
                val greedyConfig = OfflineRecognizerConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )
                createdRecognizer = OfflineRecognizer(null, greedyConfig)
                Log.i(TAG, "OfflineRecognizer loaded with greedy_search")
            }

            recognizer = createdRecognizer
            currentModelId = modelId
            return true

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize OnDeviceTranscriptionEngine", e)
            release()
            return false
        }
    }

    /**
     * Transcribes an on-disk WAV file.
     */
    @Throws(IOException::class)
    fun transcribe(audioFile: File, removeFillers: Boolean = false): String {
        val samples = AudioUtils.readWavToFloatArray(audioFile)
        return transcribe(samples, removeFillers)
    }

    /**
     * Transcribes normalized audio samples [-1.0f, 1.0f] using VAD chunking and sherpa-onnx decoding.
     * Applies name corrections and optional filler removal in accordance with contract 05 §2.4.
     */
    fun transcribe(samples: FloatArray, removeFillers: Boolean = false): String = synchronized(lock) {
        val rec = recognizer ?: throw IllegalStateException("Transcription engine not initialized. Call initialize() first.")
        val vadEngine = vad ?: throw IllegalStateException("VAD engine not initialized.")

        if (samples.isEmpty()) return ""

        Log.d(TAG, "Starting transcription for ${samples.size} samples (${samples.size / 16000.0f}s)")

        // 1. Segment audio into pauses <= 25 seconds using Silero VAD
        // Silero VAD processes audio in fixed window_size chunks (512 samples per window)
        val segments = mutableListOf<FloatArray>()
        vadEngine.reset()

        val windowSize = 512
        var offset = 0
        while (offset + windowSize <= samples.size) {
            val window = samples.copyOfRange(offset, offset + windowSize)
            vadEngine.acceptWaveform(window)
            while (!vadEngine.empty()) {
                val seg = vadEngine.front()
                if (seg.samples.isNotEmpty()) {
                    segments.add(seg.samples)
                    Log.d(TAG, "VAD detected speech segment: ${seg.samples.size} samples at offset ${seg.start}")
                }
                vadEngine.pop()
            }
            offset += windowSize
        }
        if (offset < samples.size) {
            val remaining = samples.copyOfRange(offset, samples.size)
            val window = FloatArray(windowSize)
            System.arraycopy(remaining, 0, window, 0, remaining.size)
            vadEngine.acceptWaveform(window)
        }
        vadEngine.flush()

        while (!vadEngine.empty()) {
            val seg = vadEngine.front()
            if (seg.samples.isNotEmpty()) {
                segments.add(seg.samples)
                Log.d(TAG, "VAD flush segment: ${seg.samples.size} samples at offset ${seg.start}")
            }
            vadEngine.pop()
        }

        // Fallback: If VAD detected no speech segments but audio was provided,
        // chunk directly into <= 25s slices so soft speech is never discarded
        if (segments.isEmpty()) {
            Log.d(TAG, "VAD produced 0 segments, falling back to direct chunking")
            if (samples.size <= MAX_CHUNK_SAMPLES) {
                segments.add(samples)
            } else {
                var directOffset = 0
                while (directOffset < samples.size) {
                    val len = minOf(MAX_CHUNK_SAMPLES, samples.size - directOffset)
                    val chunk = FloatArray(len)
                    System.arraycopy(samples, directOffset, chunk, 0, len)
                    segments.add(chunk)
                    directOffset += len
                }
            }
        }

        Log.d(TAG, "Total speech segments to decode: ${segments.size}")

        // 2. Decode each segment sequentially
        val textSegments = mutableListOf<String>()
        for ((idx, seg) in segments.withIndex()) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(seg, 16000)
                rec.decode(stream)
                val result = rec.getResult(stream)
                val text = result.text.trim()
                Log.d(TAG, "Segment #$idx (${seg.size} samples): raw text = '$text'")
                if (text.isNotEmpty()) {
                    textSegments.add(text)
                }
            } finally {
                stream.release()
            }
        }

        var combinedText = textSegments.joinToString(" ").trim()
        Log.i(TAG, "Decoded combined text: '$combinedText'")
        if (combinedText.isEmpty()) return ""

        // 3. Post-transcription rules: Name corrections first, then filler sounds per contract 05 §2.4
        val corrections = getEffectiveNameCorrections()
        if (corrections.isNotEmpty()) {
            combinedText = TextRules.applyNameCorrections(combinedText, corrections)
        }

        if (removeFillers) {
            val (noFillers, _) = TextRules.removeFillers(combinedText)
            combinedText = noFillers
        }

        return combinedText
    }

    /**
     * Gathers name corrections from local settings and cached server settings.
     */
    private fun getEffectiveNameCorrections(): Map<String, String> {
        val merged = mutableMapOf<String, String>()

        // 1. Cached server name corrections
        val serverJson = settingsRepository.cachedServerNameCorrectionsJson
        if (serverJson.isNotBlank()) {
            try {
                val obj = org.json.JSONObject(serverJson)
                for (k in obj.keys()) {
                    merged[k] = obj.getString(k)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing cached server name corrections", e)
            }
        }

        // 2. Local name corrections (take precedence over server)
        val local = TextRules.parseCorrectionsText(settingsRepository.localNameCorrections)
        merged.putAll(local)

        return merged
    }

    /**
     * Builds hotwords file containing shared vocabulary and "write as" replacement words.
     */
    private fun prepareHotwordsFile(modelId: String, tokensFile: File): File? {
        val words = mutableSetOf<String>()

        // Add shared/pending vocabulary
        words.addAll(settingsRepository.cachedServerVocabulary)
        words.addAll(settingsRepository.pendingVocabularyQueue)

        // Add the "write as" target terms from name corrections
        val corrections = getEffectiveNameCorrections()
        for ((_, writeAs) in corrections) {
            if (writeAs.isNotBlank()) {
                words.add(writeAs.trim())
            }
        }

        val cleanWords = words
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('\n') }
            .distinct()

        if (cleanWords.isEmpty()) return null

        val modelDir = modelManager.getModelDir(modelId)
        val hotwordsFile = File(modelDir, "hotwords.txt")
        try {
            FileOutputStream(hotwordsFile).bufferedWriter().use { writer ->
                for (w in cleanWords) {
                    writer.write(w)
                    writer.newLine()
                }
            }
            return hotwordsFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write hotwords file", e)
            return null
        }
    }

    /**
     * Releases native recognizer and VAD resources.
     */
    fun release() = synchronized(lock) {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing OfflineRecognizer", e)
        }
        try {
            vad?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Vad", e)
        }
        recognizer = null
        vad = null
        currentModelId = null
    }
}
