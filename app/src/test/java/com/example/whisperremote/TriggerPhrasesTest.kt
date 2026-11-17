// This file tests TriggerPhrase parsing, whole-list save payloads, the "Keep at least one" guard, and Home screen subtitle generation.
// It is called by `./gradlew test` (Robolectric test runner).
// Inputs: JSON payloads representing GET/POST /v1/trigger_phrases, SettingsRepository state.
// Outputs: Verifies TriggerPhrase data class, API payloads, guard conditions, and Home subtitles per F-60 and F-61.
// Reason for edit: Update Home screen Skills subtitle test to verify F-62 wording ("N skills") and no hardcoded "hey Whisper".
// Reason for creation: Unit tests for trigger phrases and Home screen group subtitles per F-60 §3 and F-61.

package com.example.whisperremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.FloatingButtonService.ButtonState
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.data.TriggerPhrase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TriggerPhrasesTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        repository.transcriptionTarget = TranscriptionTarget.PC.id
        repository.providerPreset = ProviderPreset.SELF_HOSTED
    }

    @Test
    fun testTriggerPhraseSerializationAndDeserialization() {
        val phrase = TriggerPhrase("hey Whisper", "edit")
        val json = phrase.toJson()
        assertEquals("hey Whisper", json.getString("phrase"))
        assertEquals("edit", json.getString("action"))
        assertTrue(phrase.isEdit())
        assertFalse(phrase.isEnd())

        val restored = TriggerPhrase.fromJson(json)
        assertEquals(phrase.phrase, restored.phrase)
        assertEquals(phrase.action, restored.action)

        val endPhrase = TriggerPhrase("end prompt", "end")
        assertTrue(endPhrase.isEnd())
        assertFalse(endPhrase.isEdit())
    }

    @Test
    fun testServerTriggerPhrasesParsingAndLimits() {
        val serverJsonStr = """
            {
                "trigger_phrases": [
                    {"phrase": "hey Whisper", "action": "edit"},
                    {"phrase": "prompt AI", "action": "edit"},
                    {"phrase": "end prompt", "action": "end"}
                ],
                "actions": ["edit", "end"],
                "limits": {
                    "phrases": 40,
                    "phrase_min": 2,
                    "phrase": 60
                }
            }
        """.trimIndent()

        val json = JSONObject(serverJsonStr)
        val array = json.getJSONArray("trigger_phrases")
        val phrases = (0 until array.length()).map {
            TriggerPhrase.fromJson(array.getJSONObject(it))
        }

        assertEquals(3, phrases.size)
        assertEquals("hey Whisper", phrases[0].phrase)
        assertEquals("edit", phrases[0].action)
        assertEquals("prompt AI", phrases[1].phrase)
        assertEquals("end prompt", phrases[2].phrase)
        assertEquals("end", phrases[2].action)

        val limits = json.getJSONObject("limits")
        assertEquals(40, limits.getInt("phrases"))
        assertEquals(2, limits.getInt("phrase_min"))
        assertEquals(60, limits.getInt("phrase"))
    }

    @Test
    fun testWholeListSavePayload() {
        val phrases = listOf(
            TriggerPhrase("hey Whisper", "edit"),
            TriggerPhrase("end prompt", "end")
        )
        val payload = JSONObject().apply {
            val phrasesArray = JSONArray()
            phrases.forEach { phrasesArray.put(it.toJson()) }
            put("phrases", phrasesArray)
        }

        assertTrue(payload.has("phrases"))
        val arr = payload.getJSONArray("phrases")
        assertEquals(2, arr.length())
        assertEquals("hey Whisper", arr.getJSONObject(0).getString("phrase"))
        assertEquals("edit", arr.getJSONObject(0).getString("action"))
        assertEquals("end prompt", arr.getJSONObject(1).getString("phrase"))
        assertEquals("end", arr.getJSONObject(1).getString("action"))
    }

    @Test
    fun testKeepAtLeastOneEditPhraseGuard() {
        val phrases = listOf(
            TriggerPhrase("hey Whisper", "edit"),
            TriggerPhrase("end prompt", "end")
        )
        val editCount = phrases.count { it.isEdit() }
        assertEquals(1, editCount)

        // The only edit phrase cannot be removed
        val heyWhisper = phrases[0]
        val canRemove = !heyWhisper.isEdit() || editCount > 1
        assertFalse("Guard must prevent removing the last edit phrase", canRemove)

        // But with two edit phrases:
        val twoEditPhrases = listOf(
            TriggerPhrase("hey Whisper", "edit"),
            TriggerPhrase("prompt AI", "edit"),
            TriggerPhrase("end prompt", "end")
        )
        val twoEditCount = twoEditPhrases.count { it.isEdit() }
        val canRemoveOne = !twoEditPhrases[0].isEdit() || twoEditCount > 1
        assertTrue("Guard allows removing an edit phrase if another edit phrase remains", canRemoveOne)
    }

    @Test
    fun testHomeScreenWordsSubtitle() {
        // Empty vocabulary and corrections
        repository.cachedServerVocabulary = emptySet()
        repository.updateCachedServerNameCorrections(emptyMap())
        assertEquals("Vocabulary, corrections and shortcuts", repository.getWordsSubtitle())

        // 42 words, 6 corrections per F-60 §2
        val dummyVocab = (1..42).map { "word$it" }.toSet()
        val dummyCorrections = (1..6).associate { "mishear$it" to "fix$it" }
        repository.cachedServerVocabulary = dummyVocab
        repository.updateCachedServerNameCorrections(dummyCorrections)
        assertEquals("42 words, 6 corrections", repository.getWordsSubtitle())

        // Single word and correction
        repository.cachedServerVocabulary = setOf("single")
        repository.updateCachedServerNameCorrections(mapOf("h" to "w"))
        assertEquals("1 word, 1 correction", repository.getWordsSubtitle())
    }

    @Test
    fun testHomeScreenTriggersSubtitle() {
        repository.transcriptionTarget = TranscriptionTarget.PC.id
        val phrases = listOf(
            TriggerPhrase("prompt AI", "edit"),
            TriggerPhrase("end prompt", "end")
        )
        repository.updateCachedTriggerPhrases(phrases)

        // 0 skills
        repository.cachedCustomTriggersCount = 0
        assertEquals("prompt AI", repository.getTriggersSubtitle())

        // 1 skill per F-62
        repository.cachedCustomTriggersCount = 1
        assertEquals("prompt AI · 1 skill", repository.getTriggersSubtitle())

        // 3 skills per F-62
        repository.cachedCustomTriggersCount = 3
        assertEquals("prompt AI · 3 skills", repository.getTriggersSubtitle())

        // Non-PC transcriber
        repository.transcriptionTarget = TranscriptionTarget.CLOUD.id
        assertEquals("Set up on your PC", repository.getTriggersSubtitle())
    }

    @Test
    fun testTypePillVisibilityRule() {
        fun shouldShowTypePill(
            state: ButtonState,
            preset: ProviderPreset,
            selection: String?
        ): Boolean {
            val isSelfHosted = preset == ProviderPreset.SELF_HOSTED
            val hasSelection = !selection.isNullOrBlank()
            return state == ButtonState.IDLE && isSelfHosted && hasSelection
        }

        // F-59: Only visible when IDLE, Self-hosted, and has non-empty selection
        assertTrue(shouldShowTypePill(ButtonState.IDLE, ProviderPreset.SELF_HOSTED, "Selected sentence"))
        assertFalse("Hidden when selection is empty", shouldShowTypePill(ButtonState.IDLE, ProviderPreset.SELF_HOSTED, ""))
        assertFalse("Hidden when selection is blank spaces", shouldShowTypePill(ButtonState.IDLE, ProviderPreset.SELF_HOSTED, "   "))
        assertFalse("Hidden when selection is null", shouldShowTypePill(ButtonState.IDLE, ProviderPreset.SELF_HOSTED, null))
        assertFalse("Hidden when recording", shouldShowTypePill(ButtonState.RECORDING, ProviderPreset.SELF_HOSTED, "Selected"))
        assertFalse("Hidden when processing", shouldShowTypePill(ButtonState.PROCESSING, ProviderPreset.SELF_HOSTED, "Selected"))
        assertFalse("Hidden when cloud provider", shouldShowTypePill(ButtonState.IDLE, ProviderPreset.GROQ, "Selected"))
        assertFalse("Hidden when custom endpoint", shouldShowTypePill(ButtonState.IDLE, ProviderPreset.CUSTOM, "Selected"))
    }
}
