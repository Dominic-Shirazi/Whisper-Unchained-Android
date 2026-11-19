// This file tests TriggerPhrase parsing, whole-list save payloads, the "Keep at least one" guard, and Home screen subtitle generation.
// It is called by `./gradlew test` (Robolectric test runner).
// Inputs: JSON payloads representing GET/POST /v1/trigger_phrases, SettingsRepository state.
// Outputs: Verifies TriggerPhrase data class, API payloads, guard conditions, and Home subtitles per F-60 and F-61.
// Reason for edit: Add F-74 unit test verifying idle pill displays "✎ 1 word" / "✎ N words" following highlight changes, TalkBack label, and hides in non-PC-AI modes.
// Reason for edit: Add F-73 unit tests verifying remembered highlight across Type pill and mic tap (when live selection collapses on touch or popup windowId changes, or in custom Compose/WebView nodes) and applyQrPayload target reset.
// Reason for edit: Add F-68 unit tests for honest cached text box lifecycle on paste (tapped out -> clipboard notice, switched app -> new box, null findFocus with still-focused cached node -> pasted).
// Reason for edit: Add unit tests for F-65 verifying that a recording started with highlighted text goes to POST /dictate/v1/edit_selection and unchanged replies show "The AI made no changes."
// Reason for edit: Update Home screen Skills subtitle test to verify F-62 wording ("N skills") and no hardcoded "hey Whisper".
// Reason for creation: Unit tests for trigger phrases and Home screen group subtitles per F-60 §3 and F-61.

package com.example.whisperremote

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.FloatingButtonService.ButtonState
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.data.TriggerPhrase
import com.example.whisperremote.util.PromptPanelLogic
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.TimeUnit

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

    @Test
    fun testRecordingWithSelectionGoesToEditSelection() {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/v1").toString().trimEnd('/')
            repository.baseUrl = baseUrl
            repository.lastWorkingBaseUrl = baseUrl
            repository.transcriptionTarget = TranscriptionTarget.PC.id
            repository.providerPreset = ProviderPreset.SELF_HOSTED

            assertEquals(
                server.url("/dictate/v1/edit_selection").toString(),
                repository.getEditSelectionUrl()
            )

            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val service = Robolectric.buildService(FloatingButtonService::class.java).create().get()

            // Create a valid >44 byte dummy WAV file
            val wavFile = File(context.cacheDir, "test_highlight_rec.wav")
            wavFile.writeBytes(ByteArray(256) { 1 })

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"text":"Here is the clearer version.","instruction":"make this more easily readable","edited":true}""")
            )

            val highlight = "Line 1 of Gmail draft.\nLine 2 of Gmail draft.\nLine 3 of Gmail draft."
            service.processAudioFile(wavFile, selection = highlight)

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("Expected a request to be sent to MockWebServer", req)
            assertEquals("/dictate/v1/edit_selection", req!!.path)
            assertEquals("POST", req.method)
            val bodyUtf8 = req.body.readUtf8()
            assertTrue("Multipart body must include selection field", bodyUtf8.contains("name=\"selection\""))
            assertTrue("Multipart body must include highlighted text", bodyUtf8.contains("Line 1 of Gmail draft."))
            assertTrue("Multipart body must include audio file", bodyUtf8.contains("name=\"file\""))

            // Also verify that when edited=false or text is unchanged, "The AI made no changes." is shown
            val wavFile2 = File(context.cacheDir, "test_unchanged_rec.wav")
            wavFile2.writeBytes(ByteArray(256) { 1 })
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"text":"$highlight","instruction":"make this more easily readable","edited":false}""")
            )

            service.processAudioFile(wavFile2, selection = highlight)
            val req2 = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req2)
            assertEquals("/dictate/v1/edit_selection", req2!!.path)

            Thread.sleep(150)
            ShadowLooper.idleMainLooper()
            assertEquals("The AI made no changes.", ShadowToast.getTextOfLatestToast())
        } finally {
            server.shutdown()
        }
    }

    private class FakeEditableNode(
        var focusedState: Boolean = true,
        var editableState: Boolean = true,
        var pkg: String = "com.example.app1"
    ) : AccessibilityNodeInfo() {
        var refreshCount = 0
        val performedActions = mutableListOf<Int>()

        override fun refresh(): Boolean {
            refreshCount++
            return true
        }

        override fun isFocused(): Boolean = focusedState
        override fun isEditable(): Boolean = editableState
        override fun getPackageName(): CharSequence = pkg
        override fun getClassName(): CharSequence = "android.widget.EditText"
        override fun isPassword(): Boolean = false
        override fun getInputType(): Int = android.text.InputType.TYPE_CLASS_TEXT

        override fun performAction(action: Int): Boolean {
            performedActions.add(action)
            return action == ACTION_PASTE
        }

        override fun performAction(action: Int, arguments: Bundle?): Boolean {
            performedActions.add(action)
            return true
        }
    }

    @Test
    fun testF68CachedNodePasteScenarios() {
        val a11yService = Robolectric.buildService(DictationAccessibilityService::class.java).create().get()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Scenario (a): Tapped out of the box, nothing focused -> clipboard notice, no ACTION_PASTE
        val tappedOutBox = FakeEditableNode(focusedState = true, editableState = true, pkg = "com.example.notes")
        a11yService.focusedNode = tappedOutBox
        a11yService.activeWindowPackage = "com.example.notes"
        a11yService.findFocusOverrideForTesting = { null }

        // User taps out of the box before paste arrives
        tappedOutBox.focusedState = false
        a11yService.pasteText("Hello from scenario A")

        assertTrue("refresh() must be called on cached node before using it", tappedOutBox.refreshCount > 0)
        assertFalse(
            "ACTION_PASTE must NOT be called on a box the user has left",
            tappedOutBox.performedActions.contains(AccessibilityNodeInfo.ACTION_PASTE)
        )
        assertNull("Stale focusedNode must be cleared when no longer focused", a11yService.focusedNode)
        assertEquals("Hello from scenario A", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        // Scenario (b): Switched app during processing with a box focused there -> pasted in the new box
        val oldAppBox = FakeEditableNode(focusedState = true, editableState = true, pkg = "com.example.app1")
        val newAppBox = FakeEditableNode(focusedState = true, editableState = true, pkg = "com.example.app2")
        a11yService.focusedNode = oldAppBox
        a11yService.lastSelectionNode = oldAppBox
        a11yService.activeWindowPackage = "com.example.app1"
        a11yService.activeWindowId = 10

        // Active window changes to app2
        @Suppress("DEPRECATION")
        val winEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.example.app2"
        }
        a11yService.findFocusOverrideForTesting = { newAppBox }
        a11yService.onAccessibilityEvent(winEvent)

        a11yService.pasteText("Hello in app 2")
        assertFalse(
            "Old app box must not receive ACTION_PASTE",
            oldAppBox.performedActions.contains(AccessibilityNodeInfo.ACTION_PASTE)
        )
        assertTrue(
            "New app box must receive ACTION_PASTE",
            newAppBox.performedActions.contains(AccessibilityNodeInfo.ACTION_PASTE)
        )

        // Scenario (c): App whose findFocus(FOCUS_INPUT) returns null, but cached node is still isFocused && isEditable -> pasted
        val quirkyAppBox = FakeEditableNode(focusedState = true, editableState = true, pkg = "com.example.quirky")
        a11yService.focusedNode = quirkyAppBox
        a11yService.activeWindowPackage = "com.example.quirky"
        a11yService.findFocusOverrideForTesting = { null }

        a11yService.pasteText("Hello in quirky app")
        assertTrue("refresh() must be called on cached node", quirkyAppBox.refreshCount > 0)
        assertTrue(
            "Cached node that is still focused and editable must receive ACTION_PASTE",
            quirkyAppBox.performedActions.contains(AccessibilityNodeInfo.ACTION_PASTE)
        )
    }

    private fun runApiLevelFlowVerification(expectedSdkInt: Int) {
        assertEquals(expectedSdkInt, android.os.Build.VERSION.SDK_INT)

        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        // 1. Verify notification permission check on API 31 vs API 33
        val notifAllowed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        assertTrue("Notifications should be enabled on API $expectedSdkInt", notifAllowed)

        // 2. Start FloatingButtonService & DictationAccessibilityService on API 31/33
        val floatService = Robolectric.buildService(FloatingButtonService::class.java).create().get()
        val a11yService = Robolectric.buildService(DictationAccessibilityService::class.java).create().get()
        val activeBox = FakeEditableNode(focusedState = true, editableState = true, pkg = "com.example.chat")
        a11yService.focusedNode = activeBox
        a11yService.findFocusOverrideForTesting = { activeBox }

        floatService.onTextFieldFocused()
        ShadowLooper.idleMainLooper()

        // 3. Record and paste with Accessibility Service ON, and Clipboard mode with it OFF
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/v1").toString().trimEnd('/')
            repository.baseUrl = baseUrl
            repository.lastWorkingBaseUrl = baseUrl
            repository.transcriptionTarget = TranscriptionTarget.PC.id
            repository.providerPreset = ProviderPreset.SELF_HOSTED
            repository.injectionMode = com.example.whisperremote.data.InjectionMode.ACCESSIBILITY

            val wavFile = File(context.cacheDir, "api_${expectedSdkInt}_rec.wav")
            wavFile.writeBytes(ByteArray(256) { 1 })
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Dictated on API $expectedSdkInt"}"""))
            floatService.processAudioFile(wavFile)
            val a11yReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(a11yReq)
            assertEquals("/v1/audio/transcriptions", a11yReq!!.path)
            Thread.sleep(150)
            ShadowLooper.idleMainLooper()

            // Trigger accessibility paste via pasteText verification
            a11yService.pasteText("Dictated on API $expectedSdkInt")
            assertTrue(
                "Expected ACTION_PASTE on API $expectedSdkInt",
                activeBox.performedActions.contains(AccessibilityNodeInfo.ACTION_PASTE)
            )

            // 4. Clipboard mode with Accessibility off
            repository.injectionMode = com.example.whisperremote.data.InjectionMode.CLIPBOARD
            val wavFileClip = File(context.cacheDir, "api_${expectedSdkInt}_clip.wav")
            wavFileClip.writeBytes(ByteArray(256) { 1 })
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Clipboard text on API $expectedSdkInt"}"""))
            floatService.processAudioFile(wavFileClip)
            val clipReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(clipReq)
            assertEquals("/v1/audio/transcriptions", clipReq!!.path)
            Thread.sleep(150)
            ShadowLooper.idleMainLooper()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Clipboard text on API $expectedSdkInt", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

            // 5. Highlight chip and rewrite on API 31/33
            repository.injectionMode = com.example.whisperremote.data.InjectionMode.ACCESSIBILITY
            val wavFileRewrite = File(context.cacheDir, "api_${expectedSdkInt}_rewrite.wav")
            wavFileRewrite.writeBytes(ByteArray(256) { 1 })
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"text":"Polite version on API $expectedSdkInt","instruction":"make it polite","edited":true}"""
                )
            )
            floatService.processAudioFile(wavFileRewrite, selection = "Original highlighted words")
            val rewriteReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(rewriteReq)
            assertEquals("/dictate/v1/edit_selection", rewriteReq!!.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    @Config(sdk = [31])
    fun testF70EndToEndFlowsOnApi31() {
        runApiLevelFlowVerification(31)
    }

    @Test
    @Config(sdk = [33])
    fun testF70EndToEndFlowsOnApi33() {
        runApiLevelFlowVerification(33)
    }

    @Test
    @Config(qualifiers = "w864dp-h864dp-xxhdpi")
    fun testF71FloatingButtonAndSheetsSurviveFoldAndRotate() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val floatService = Robolectric.buildService(FloatingButtonService::class.java).create().get()
        floatService.onTextFieldFocused()
        ShadowLooper.idleMainLooper()

        // Unfolded 1:1 display (864dp @ xxhdpi 3x = 2592px wide)
        val unfoldedWidth = floatService.resources.displayMetrics.widthPixels
        assertEquals(2592, unfoldedWidth)

        // Verify initial x is on the right edge of the 2592px unfolded screen (> 2000px)
        val windowParamsField = FloatingButtonService::class.java.getDeclaredField("windowParams").apply { isAccessible = true }
        val params = windowParamsField.get(floatService) as android.view.WindowManager.LayoutParams
        assertTrue("Initial button x (${params.x}) should be on right edge of unfolded screen", params.x > 2000)

        // Simulate folding to 10:16 outer screen (1080x1728 px)
        floatService.resources.displayMetrics.widthPixels = 1080
        floatService.resources.displayMetrics.heightPixels = 1728
        floatService.onConfigurationChanged(floatService.resources.configuration)

        // Verify floating button x position was clamped / re-snapped inside the new 1080px outer screen
        assertTrue(
            "Floating button x (${params.x}) must remain inside 1080px folded screen width",
            params.x in 0..1000
        )
        assertTrue(
            "Floating button y (${params.y}) must remain inside 1728px folded screen height",
            params.y in 0..1650
        )
    }

    private class FakeSelectionNode(
        var textValue: String = "Keep note with a broken phrase inside",
        var selStart: Int = 17,
        var selEnd: Int = 30,
        var focusedState: Boolean = true,
        var rawEditableFlag: Boolean = false, // Simulates Compose / WebView node where raw isEditable=false but className/actions are editable
        var pkg: String = "com.anthropic.claude"
    ) : AccessibilityNodeInfo() {
        val performedActions = mutableListOf<Int>()

        override fun refresh(): Boolean = true
        override fun isFocused(): Boolean = focusedState
        override fun isEditable(): Boolean = rawEditableFlag
        override fun getPackageName(): CharSequence = pkg
        override fun getClassName(): CharSequence = "android.widget.EditText"
        override fun isPassword(): Boolean = false
        override fun getInputType(): Int = android.text.InputType.TYPE_CLASS_TEXT
        override fun getText(): CharSequence = textValue
        override fun getTextSelectionStart(): Int = selStart
        override fun getTextSelectionEnd(): Int = selEnd

        override fun performAction(action: Int): Boolean {
            performedActions.add(action)
            if (action == ACTION_PASTE) {
                return false // Force splice via ACTION_SET_TEXT so we can assert the exact replaced range
            }
            return true
        }

        override fun performAction(action: Int, arguments: Bundle?): Boolean {
            performedActions.add(action)
            if (action == ACTION_SET_SELECTION && arguments != null) {
                selStart = arguments.getInt(ACTION_ARGUMENT_SELECTION_START_INT, selStart)
                selEnd = arguments.getInt(ACTION_ARGUMENT_SELECTION_END_INT, selEnd)
                return true
            }
            if (action == ACTION_SET_TEXT && arguments != null) {
                val cs = arguments.getCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                if (cs != null) {
                    textValue = cs.toString()
                    return true
                }
            }
            return true
        }
    }

    @Test
    fun testF73TapUsesRememberedHighlightWhenLiveSelectionDropsOnTouchOrPopup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val a11yController = Robolectric.buildService(DictationAccessibilityService::class.java).create()
        val a11yService = a11yController.get()
        val instanceField = DictationAccessibilityService.Companion::class.java.superclass
            ?.let { DictationAccessibilityService::class.java.getDeclaredField("instance") }
            ?: DictationAccessibilityService::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, a11yService)

        val floatService = Robolectric.buildService(FloatingButtonService::class.java).create().get()
        repository.providerPreset = ProviderPreset.SELF_HOSTED
        repository.transcriptionTarget = TranscriptionTarget.PC.id

        // 1. User highlights "broken phrase" (indices 17..30) in a Compose/Keep box where raw isEditable=false
        val box = FakeSelectionNode(
            textValue = "Keep note with a broken phrase inside",
            selStart = 17,
            selEnd = 30,
            focusedState = true,
            rawEditableFlag = false,
            pkg = "com.anthropic.claude"
        )
        a11yService.activeWindowPackage = "com.anthropic.claude"
        a11yService.activeWindowId = 10
        a11yService.focusedNode = box
        a11yService.findFocusOverrideForTesting = { box }

        floatService.onTextFieldFocused()
        ShadowLooper.idleMainLooper()

        // Type pill must see "broken phrase" and remember it
        floatService.updateTypePillVisibility()
        assertEquals("broken phrase", floatService.rememberedSelectionFromTypePill)
        assertEquals("broken phrase", a11yService.getSelectedTextOrRemembered())

        // 2. Floating Action Mode Copy/Paste toolbar pops up (TYPE_WINDOW_STATE_CHANGED with same package, popup windowId=99)
        // AND touching the floating mic button causes the underlying app to collapse its live selection to a cursor (17..17)
        @Suppress("DEPRECATION")
        val popupEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.anthropic.claude"
            // windowId change from floating Action Mode toolbar
        }
        a11yService.onAccessibilityEvent(popupEvent)
        box.selStart = 17
        box.selEnd = 17

        // Live getSelectedText() is now null because the app dropped its selection on touch
        assertNull("Live selection collapsed on touch", a11yService.getSelectedText())
        // But getSelectedTextOrRemembered() still returns the guarded remembered highlight!
        assertEquals("broken phrase", a11yService.getSelectedTextOrRemembered())

        // 3. Tapping the mic button captures the remembered highlight, shows "✎ 2 words", and replaces the exact range
        val captured = a11yService.getSelectedTextOrRemembered() ?: floatService.rememberedSelectionFromTypePill
        assertEquals("broken phrase", captured)
        assertEquals("✎ 2 words", PromptPanelLogic.highlightChipText(PromptPanelLogic.highlightWordCount(captured), isRewrite = true))

        a11yService.replaceOrInsertText("polished phrase", replaceSelection = true, expectedSelection = captured)
        assertEquals("Keep note with a polished phrase inside", box.textValue)

        // 4. Guard check: switching to a different app clears any remembered highlight
        a11yService.rememberedHighlight = DictationAccessibilityService.RememberedHighlight(
            text = "stale highlight",
            packageName = "com.anthropic.claude",
            windowId = 10,
            selStart = 0,
            selEnd = 5,
            node = box
        )
        @Suppress("DEPRECATION")
        val appSwitchEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.google.android.keep"
        }
        a11yService.onAccessibilityEvent(appSwitchEvent)
        assertNull("Remembered highlight must be cleared when switching to a different package", a11yService.getSelectedTextOrRemembered())
    }

    @Test
    fun testF73ApplyQrPayloadResetsTranscriptionTargetToPc() {
        repository.transcriptionTarget = TranscriptionTarget.ON_DEVICE.id
        repository.providerPreset = ProviderPreset.GROQ

        val pin = "a".repeat(64)
        val qrJson = """{"v":2,"base_url":"https://100.64.0.1:5000/v1","cert_sha256":"$pin","model":"large-v3-turbo","name":"Dom PC"}"""
        assertTrue(repository.importQrPayload(qrJson))
        assertEquals(ProviderPreset.SELF_HOSTED, repository.providerPreset)
        assertEquals(TranscriptionTarget.PC.id, repository.transcriptionTarget)
    }

    @Test
    fun testF74IdleHighlightPillShowsPencilAndLiveWordCountAndHidesWithoutPcAi() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val a11yService = Robolectric.buildService(DictationAccessibilityService::class.java).create().get()
        val instanceField = DictationAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instanceField.set(null, a11yService)

        val floatService = Robolectric.buildService(FloatingButtonService::class.java).create().get()
        repository.providerPreset = ProviderPreset.SELF_HOSTED
        repository.transcriptionTarget = TranscriptionTarget.PC.id

        val box = FakeSelectionNode(
            textValue = "alpha beta gamma delta",
            selStart = 0,
            selEnd = 5, // "alpha" -> 1 word
            focusedState = true,
            rawEditableFlag = true,
            pkg = "com.example.notes"
        )
        a11yService.activeWindowPackage = "com.example.notes"
        a11yService.focusedNode = box
        a11yService.findFocusOverrideForTesting = { box }

        floatService.onTextFieldFocused()
        ShadowLooper.idleMainLooper()

        // 1 word highlighted -> "✎ 1 word", TalkBack "Edit 1 highlighted word by typing"
        floatService.updateTypePillVisibility()
        assertEquals(android.view.View.VISIBLE, floatService.typePillView?.visibility)
        assertEquals("✎ 1 word", floatService.typePillView?.text?.toString())
        assertEquals("Edit 1 highlighted word by typing", floatService.typePillView?.contentDescription?.toString())

        // Expand selection to 3 words -> "✎ 3 words", TalkBack "Edit 3 highlighted words by typing"
        box.selStart = 0
        box.selEnd = 16 // "alpha beta gamma"
        floatService.updateTypePillVisibility()
        assertEquals(android.view.View.VISIBLE, floatService.typePillView?.visibility)
        assertEquals("✎ 3 words", floatService.typePillView?.text?.toString())
        assertEquals("Edit 3 highlighted words by typing", floatService.typePillView?.contentDescription?.toString())

        // Switch to On-Device mode -> idle pill hides (no PC AI to rewrite with)
        repository.transcriptionTarget = TranscriptionTarget.ON_DEVICE.id
        floatService.updateTypePillVisibility()
        assertEquals(android.view.View.GONE, floatService.typePillView?.visibility)

        // Switch to Cloud preset -> idle pill stays hidden
        repository.transcriptionTarget = TranscriptionTarget.PC.id
        repository.providerPreset = ProviderPreset.GROQ
        floatService.updateTypePillVisibility()
        assertEquals(android.view.View.GONE, floatService.typePillView?.visibility)
    }
}
