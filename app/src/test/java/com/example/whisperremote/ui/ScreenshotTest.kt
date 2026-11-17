// This file provides Robolectric + Roborazzi screenshot tests for Whisper Unchained.
// It is called by the Gradle test runner (:app:testPlayDebugUnitTest).
// Inputs: Composable UI screens (HomeScreen, TranscriptionScreen, WordsTriggersScreen, AiEditingScreen,
//         PasteModeScreen, PermissionsScreen, FloatingButtonScreen, AboutScreen, IntroScreen pages 1-7).
// Outputs: Screen capture PNG files in screenshots/ (resolving to app/screenshots/) rendered across 3 device configurations:
//          Phone (S25: 1080x2340 / w360dp-h780dp-xhdpi),
//          Fold Inner (1856x2160 / w742dp-h864dp-xxhdpi),
//          Fold Cover (904x2316 / w361dp-h926dp-xhdpi),
//          in both Light and Dark One UI pure-black themes.
// Conforms to contracts/04-look-and-feel.md §7.
// Reason for edit: Add Play Store listing screenshot generator (docs/store/*.png) on clean sample data per F-64 §6.

package com.example.whisperremote.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.ApiClient
import com.example.whisperremote.R
import com.example.whisperremote.util.PromptPanelLogic
import com.example.whisperremote.data.CustomTrigger
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.data.TriggerPhrase
import com.example.whisperremote.net.TailscaleManager
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.StatusCardState
import com.example.whisperremote.ui.intro.IntroScreen
import com.example.whisperremote.ui.screens.AboutScreen
import com.example.whisperremote.ui.screens.AiEditingScreen
import com.example.whisperremote.ui.screens.FloatingButtonScreen
import com.example.whisperremote.ui.screens.HomeScreen
import com.example.whisperremote.ui.screens.PasteModeScreen
import com.example.whisperremote.ui.screens.PermissionsScreen
import com.example.whisperremote.ui.screens.TranscriptionScreen
import com.example.whisperremote.ui.screens.TriggersScreen
import com.example.whisperremote.ui.screens.WordsTriggersScreen
import com.example.whisperremote.ui.theme.WhisperTheme
import com.example.whisperremote.ui.theme.WhisperTokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {

    companion object {
        init {
            System.setProperty("roborazzi.test.record", "true")
            System.setProperty("roborazzi.record", "true")
        }
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository
    private lateinit var apiClient: ApiClient
    private lateinit var tailscaleManager: TailscaleManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
        apiClient = ApiClient(context)
        tailscaleManager = TailscaleManager(context)
        File("screenshots").mkdirs()
    }

    private fun capture(fileName: String, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                content()
            }
        }
        val targetPath = "screenshots/$fileName.png"
        composeTestRule.onRoot().captureRoboImage(targetPath)
    }

    // =========================================================================
    // S25 Phone Tests (w360dp-h780dp-xhdpi)
    // =========================================================================

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testMainLightS25() {
        capture("main_light_s25") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Dominic-PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testMainDarkS25() {
        capture("main_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Dominic-PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testTranscriptionDarkS25() {
        capture("transcription_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                TranscriptionScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testTranscriptionLightS25() {
        capture("transcription_light_s25") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                TranscriptionScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testWordsTriggersDarkS25() {
        repository.providerPreset = com.example.whisperremote.data.ProviderPreset.SELF_HOSTED
        repository.cachedServerVocabulary = setOf("Kubernetes", "Docker", "Ollama")
        repository.cachedServerNameCorrectionsJson = "{\"Corey\":\"Cory\",\"Leslie\":\"Lesley\"}"
        repository.clearPendingMutations()
        repository.queueWordMutation(
            com.example.whisperremote.data.QueuedMutation(
                type = "name",
                setNameCorrections = mapOf("Corey" to "Cory")
            )
        )
        capture("words_triggers_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                WordsTriggersScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testWordsTriggersLightS25() {
        repository.providerPreset = com.example.whisperremote.data.ProviderPreset.SELF_HOSTED
        repository.cachedServerVocabulary = setOf("Kubernetes", "Docker", "Ollama")
        repository.cachedServerNameCorrectionsJson = "{\"Corey\":\"Cory\",\"Leslie\":\"Lesley\"}"
        repository.clearPendingMutations()
        repository.queueWordMutation(
            com.example.whisperremote.data.QueuedMutation(
                type = "name",
                setNameCorrections = mapOf("Corey" to "Cory")
            )
        )
        capture("words_triggers_light_s25") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                WordsTriggersScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testAiEditingDarkS25() {
        capture("ai_editing_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                AiEditingScreen(
                    repository = repository,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testAiEditingLightS25() {
        capture("ai_editing_light_s25") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                AiEditingScreen(
                    repository = repository,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testPasteModeDarkS25() {
        capture("paste_mode_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                PasteModeScreen(
                    repository = repository,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testPasteModeLightS25() {
        capture("paste_mode_light_s25") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                PasteModeScreen(
                    repository = repository,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testFloatingButtonDarkS25() {
        capture("floating_button_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                FloatingButtonScreen(
                    repository = repository,
                    tailscaleManager = tailscaleManager,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testPermissionsDarkS25() {
        capture("permissions_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                PermissionsScreen(
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testAboutDarkS25() {
        capture("about_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                AboutScreen(
                    onNavigateBack = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testAboutLightS25() {
        capture("about_light_s25") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                AboutScreen(
                    onNavigateBack = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    // Intro Sandbox Pages 1-7
    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP1WelcomeDarkS25() {
        capture("intro_p1_welcome_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 1
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP2ConnectDarkS25() {
        capture("intro_p2_connect_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 2
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP3MicDarkS25() {
        capture("intro_p3_mic_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 3
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP4PracticeDarkS25() {
        capture("intro_p4_practice_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 4
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP5PasteDarkS25() {
        capture("intro_p5_paste_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 5
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP6BatteryDarkS25() {
        capture("intro_p6_battery_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 6
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testIntroP7FinishDarkS25() {
        capture("intro_p7_finish_dark_s25") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 7
                )
            }
        }
    }

    // =========================================================================
    // Fold Inner Tests (w742dp-h864dp-xxhdpi)
    // =========================================================================

    @Test
    @Config(qualifiers = "w742dp-h864dp-xxhdpi")
    fun testMainLightFoldInner() {
        capture("main_light_fold_inner") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Dominic-PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w742dp-h864dp-xxhdpi")
    fun testMainDarkFoldInner() {
        capture("main_dark_fold_inner") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Dominic-PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w742dp-h864dp-xxhdpi")
    fun testTranscriptionDarkFoldInner() {
        capture("transcription_dark_fold_inner") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                TranscriptionScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w742dp-h864dp-xxhdpi")
    fun testAiEditingDarkFoldInner() {
        capture("ai_editing_dark_fold_inner") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                AiEditingScreen(
                    repository = repository,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w742dp-h864dp-xxhdpi")
    fun testIntroPracticeDarkFoldInner() {
        capture("intro_practice_dark_fold_inner") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 4
                )
            }
        }
    }

    // =========================================================================
    // Fold Cover Tests (w361dp-h926dp-xhdpi)
    // =========================================================================

    @Test
    @Config(qualifiers = "w361dp-h926dp-xhdpi")
    fun testMainLightFoldCover() {
        capture("main_light_fold_cover") {
            WhisperTheme(darkTheme = false, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Dominic-PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w361dp-h926dp-xhdpi")
    fun testMainDarkFoldCover() {
        capture("main_dark_fold_cover") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Dominic-PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w361dp-h926dp-xhdpi")
    fun testTranscriptionDarkFoldCover() {
        capture("transcription_dark_fold_cover") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                TranscriptionScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w361dp-h926dp-xhdpi")
    fun testAiEditingDarkFoldCover() {
        capture("ai_editing_dark_fold_cover") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                AiEditingScreen(
                    repository = repository,
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w361dp-h926dp-xhdpi")
    fun testIntroPracticeDarkFoldCover() {
        capture("intro_practice_dark_fold_cover") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                IntroScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onScanQrCode = {},
                    onFinishIntro = {},
                    initialPage = 4
                )
            }
        }
    }

    // =========================================================================
    // Play Store Listing Screenshots (docs/store/*.png) per F-64 §6
    // =========================================================================

    private fun captureStore(fileName: String, content: @Composable () -> Unit) {
        val storeDir = if (File("../docs").exists() || File("..").canonicalFile.name == "WhisperRemote_for_Android") {
            File("../docs/store")
        } else {
            File("docs/store")
        }
        storeDir.mkdirs()
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                content()
            }
        }
        val outFile = File(storeDir, "$fileName.png")
        composeTestRule.onRoot().captureRoboImage(outFile.path)
    }

    private val sampleStoreSkills = listOf(
        CustomTrigger(
            id = "email",
            name = "Email from notes",
            phrases = listOf("skill email", "prompt email"),
            prompt = "Turn these rough notes into a clear, polite email.",
            enabled = true,
            libraryId = "email",
            libraryVersion = 2,
            category = "Work writing",
            description = "Say the gist; get a ready-to-send email",
            model = "any"
        ),
        CustomTrigger(
            id = "meeting-notes",
            name = "Meeting notes",
            phrases = listOf("skill meeting notes", "prompt meeting"),
            prompt = "Organize into Decisions, Action items, and Open questions.",
            enabled = true,
            libraryId = "meeting-notes",
            libraryVersion = 2,
            category = "Work writing",
            description = "Decisions, action items, and open questions",
            model = "any"
        ),
        CustomTrigger(
            id = "bullets",
            name = "Bullet points",
            phrases = listOf("skill bullets", "prompt bullets"),
            prompt = "Turn the dictated thoughts into concise bullet points.",
            enabled = true,
            libraryId = "bullets",
            libraryVersion = 2,
            category = "Everyday",
            description = "Turns a rambling paragraph into clean bullets",
            model = "any"
        ),
        CustomTrigger(
            id = "shorter",
            name = "Shorter",
            phrases = listOf("skill shorter", "prompt shorter"),
            prompt = "Cut the length in half while keeping every key fact.",
            enabled = false,
            libraryId = "shorter",
            libraryVersion = 2,
            category = "Everyday",
            description = "Says the same thing in half the words",
            model = "any"
        )
    )

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore01Home() {
        repository.transcriptionTarget = TranscriptionTarget.PC.id
        repository.providerPreset = ProviderPreset.SELF_HOSTED
        repository.cleanupEnabled = true
        repository.cleanupTone = "neutral"
        repository.cachedServerVocabulary = (1..18).map { "term$it" }.toSet()
        repository.updateCachedServerNameCorrections(
            mapOf("kuburnetes" to "Kubernetes", "fast api" to "FastAPI", "tail scale" to "Tailscale")
        )
        repository.updateCachedTriggerPhrases(
            listOf(TriggerPhrase("prompt AI", "edit"), TriggerPhrase("end prompt", "end"))
        )
        repository.cachedCustomTriggersCount = sampleStoreSkills.size

        captureStore("01-home") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                HomeScreen(
                    repository = repository,
                    statusCardState = StatusCardState.Connected("Your PC", "large-v3-turbo"),
                    onNavigateToTranscription = {},
                    onNavigateToWordsTriggers = {},
                    onNavigateToAiEditing = {},
                    onNavigateToPasteMode = {},
                    onNavigateToFloatingButton = {},
                    onNavigateToPermissions = {},
                    onNavigateToAbout = {},
                    onLaunchIntro = {}
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore02FloatingMicRecordingNotes() {
        captureStore("02-floating-mic-recording-notes") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101318))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Notes",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE7E9EC)
                            )
                            Text(
                                text = "Saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF868E9C)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF181C24),
                            border = BorderStroke(1.dp, Color(0xFF2B313C))
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Sprint Planning Summary",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE7E9EC)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "• Shipped on-device Parakeet speech recognition with offline fallback.\n\n" +
                                        "• Added voice skills so a short phrase runs a saved instruction.\n\n" +
                                        "• Next step: send the release checklist by Friday|",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFFD5D9E0),
                                    lineHeight = 24.sp,
                                    modifier = Modifier.padding(end = 84.dp)
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .offset(y = (-20).dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFD9694A)
                        ) {
                            Text(
                                text = "● REC 00:04",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Image(
                            painter = painterResource(R.drawable.whisper_remote_record),
                            contentDescription = "Recording microphone button",
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xEE1B1E25),
                            border = BorderStroke(1.dp, Color(0xFF2B313C))
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Grammar & flow",
                                    color = Color(0xFF868E9C),
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "Fix grammar",
                                    color = Color(0xFFE7E9EC),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF4B5363)))
                                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF2FB79A)))
                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF4B5363)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore03Skills() {
        repository.transcriptionTarget = TranscriptionTarget.PC.id
        repository.providerPreset = ProviderPreset.SELF_HOSTED
        repository.updateCachedTriggerPhrases(
            listOf(TriggerPhrase("prompt AI", "edit"), TriggerPhrase("end prompt", "end"))
        )
        captureStore("03-skills") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                TriggersScreen(
                    repository = repository,
                    apiClient = apiClient,
                    onNavigateBack = {},
                    initialSkills = sampleStoreSkills
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore04SkillLibrary() {
        val libraryShowcase = sampleStoreSkills + listOf(
            CustomTrigger(
                id = "ticket",
                name = "Bug ticket",
                phrases = listOf("prompt ticket", "ticket this"),
                prompt = "Format as a bug ticket with Steps to reproduce, Expected, and Actual.",
                enabled = true,
                libraryId = "ticket",
                libraryVersion = 1,
                category = "Work writing",
                description = "Turns a spoken bug report into a structured issue",
                model = "any"
            ),
            CustomTrigger(
                id = "spec",
                name = "Feature spec",
                phrases = listOf("skill spec", "prompt spec"),
                prompt = "Write a concise engineering specification from these notes.",
                enabled = true,
                libraryId = "spec",
                libraryVersion = 2,
                category = "Work writing",
                description = "Rough idea to structured feature spec",
                model = "strong"
            )
        )
        captureStore("04-skill-library") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Skill library") },
                            navigationIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Skills other people have shared. Add one, then change its phrase or instruction to suit you.\n• A skill is only instructions for your AI: read them before adding",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val grouped = libraryShowcase.groupBy { it.category ?: "General" }
                        grouped.forEach { (category, items) ->
                            Text(
                                text = category,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                            )
                            items.take(2).forEach { item ->
                                val isAdded = sampleStoreSkills.any { it.id == item.id }
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (item.model == "strong") "Needs a strong model" else "Works with small local models",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.description.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Say: ${item.phrases.joinToString(" or ") { "\"$it\"" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Show the instruction",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                                            )
                                            if (isAdded) {
                                                OutlinedButton(
                                                    onClick = {},
                                                    enabled = false,
                                                    shape = RoundedCornerShape(16.dp)
                                                ) {
                                                    Text("✓ Added")
                                                }
                                            } else {
                                                Button(
                                                    onClick = {},
                                                    shape = RoundedCornerShape(16.dp)
                                                ) {
                                                    Text("Add")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore05PairingQrPlaceholder() {
        captureStore("05-pairing-qr-placeholder") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Transcription") },
                            navigationIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        SettingsGroup(
                            header = "Computer connection",
                            modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Pair with your computer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Open Settings on your PC → Devices & Apps → Show pairing QR",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(18.dp))

                                Box(
                                    modifier = Modifier
                                        .size(168.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White)
                                        .border(2.dp, WhisperTokens.BrandTeal, RoundedCornerShape(16.dp))
                                        .padding(18.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val cell = size.width / 7f
                                        val dark = Color(0xFF15171C)
                                        val accent = Color(0xFF2FB79A)
                                        fun drawFinder(gx: Int, gy: Int) {
                                            drawRect(dark, Offset(gx * cell, gy * cell), Size(cell * 2.5f, cell * 2.5f))
                                            drawRect(Color.White, Offset((gx + 0.4f) * cell, (gy + 0.4f) * cell), Size(cell * 1.7f, cell * 1.7f))
                                            drawRect(accent, Offset((gx + 0.8f) * cell, (gy + 0.8f) * cell), Size(cell * 0.9f, cell * 0.9f))
                                        }
                                        drawFinder(0, 0)
                                        drawFinder(4, 0)
                                        drawFinder(0, 4)
                                        drawRect(dark, Offset(3.1f * cell, 3.1f * cell), Size(cell * 0.8f, cell * 0.8f))
                                        drawRect(dark, Offset(4.5f * cell, 4.5f * cell), Size(cell * 0.8f, cell * 0.8f))
                                        drawRect(dark, Offset(5.6f * cell, 5.6f * cell), Size(cell * 0.8f, cell * 0.8f))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Sample QR placeholder · Pinned TLS 1.3 (SHA-256)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    shape = RoundedCornerShape(25.dp)
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan pairing code")
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Text("Test connection")
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "✓ Connected: your PC runs large-v3-turbo",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WhisperTokens.BrandTeal,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "In use: https://100.64.0.10:5000/v1 (also: https://192.168.1.50:5000/v1)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore06OnDeviceMode() {
        captureStore("06-on-device-mode") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("On-Device Speech Models") },
                            navigationIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SettingsGroup(
                            header = "On-device processing",
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        tint = WhisperTokens.BrandTeal,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Private and offline",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Speech is recognized on this phone; your audio never leaves it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        SettingsGroup(
                            header = "On-device speech model",
                            footer = "Models are downloaded directly from Hugging Face and verified with SHA-256 before activation."
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Model 1: Recommended 0.6B Unified
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Recommended · most accurate (633 MB)",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = WhisperTokens.BrandTeal
                                        )
                                        Text(
                                            text = "Parakeet unified-en 0.6B · Active ✓",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Licensed by NVIDIA Corporation under the NVIDIA Open Model License",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                SettingsDivider(insetStart = false)
                                // Model 2: 110M Small & Fast
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Small and fast (136 MB)",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Parakeet TDT 110M (English) · Downloaded",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(onClick = {}, shape = RoundedCornerShape(16.dp)) {
                                        Text("Use")
                                    }
                                }
                                SettingsDivider(insetStart = false)
                                // Model 3: 0.6B v3 Multilingual
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "25 European languages (671 MB)",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Parakeet TDT 0.6B v3 · Auto language detection",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(onClick = {}, shape = RoundedCornerShape(16.dp)) {
                                        Text("Download")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Reason for edit: Render F-66 / F-66b highlight chip ("✎ 113 words" and struck-through "113 words") and "Your 113 highlighted words" sheet screenshot
    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun testStore07HighlightChipAndSheet() {
        captureStore("07-highlight-chip-and-sheet") {
            WhisperTheme(darkTheme = true, dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0E14))
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top section: Side-by-side recording button + chip states (Rewrite vs Type over)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Top
                        ) {
                            // State A: Rewrite mode (default when PC AI is available)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Mic",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Image(
                                    painter = painterResource(R.drawable.whisper_remote_record),
                                    contentDescription = "Recording",
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF15171C), RoundedCornerShape(16.dp))
                                        .border(1.dp, Color(0xFF2FB79A), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = PromptPanelLogic.highlightChipText(113, isRewrite = true),
                                        color = Color(0xFF2FB79A),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // State B: Type over mode (struck through)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Mic",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Image(
                                    painter = painterResource(R.drawable.whisper_remote_record),
                                    contentDescription = "Recording",
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF15171C), RoundedCornerShape(16.dp))
                                        .border(1.dp, Color(0xFF2B313C), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = PromptPanelLogic.highlightChipText(113, isRewrite = false),
                                        color = Color(0xFFE7E9EC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                }
                            }
                        }

                        // Center/Bottom section: Open sheet when tapping the chip during recording
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1B1E25), RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0xFF2B313C), RoundedCornerShape(20.dp))
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                        ) {
                            // Header: REC dot + timer + title + close
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFFD9694A), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "REC",
                                    color = Color(0xFFD9694A),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "00:04",
                                    color = Color(0xFFE7E9EC),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = PromptPanelLogic.highlightSheetTitle(113),
                                    color = Color(0xFFE7E9EC),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "✕",
                                    color = Color(0xFF868E9C),
                                    fontSize = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF2B313C))
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Option 1: Rewrite them (selected)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E2D2B), RoundedCornerShape(14.dp))
                                    .border(1.5.dp, Color(0xFF2FB79A), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = "✓  ${PromptPanelLogic.HIGHLIGHT_REWRITE_TITLE}",
                                    color = Color(0xFF2FB79A),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = PromptPanelLogic.HIGHLIGHT_REWRITE_BODY,
                                    color = Color(0xFFC4CAD4),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Option 2: Type over them (unselected)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF222731), RoundedCornerShape(14.dp))
                                    .border(1.5.dp, Color(0xFF2B313C), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = PromptPanelLogic.HIGHLIGHT_TYPE_OVER_TITLE,
                                    color = Color(0xFFE7E9EC),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = PromptPanelLogic.HIGHLIGHT_TYPE_OVER_BODY,
                                    color = Color(0xFFC4CAD4),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Done button
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .background(Color(0xFF2FB79A), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Done",
                                    color = Color(0xFF15171C),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
