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

package com.example.whisperremote.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.whisperremote.ApiClient
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.net.TailscaleManager
import com.example.whisperremote.ui.components.StatusCardState
import com.example.whisperremote.ui.intro.IntroScreen
import com.example.whisperremote.ui.screens.AboutScreen
import com.example.whisperremote.ui.screens.AiEditingScreen
import com.example.whisperremote.ui.screens.FloatingButtonScreen
import com.example.whisperremote.ui.screens.HomeScreen
import com.example.whisperremote.ui.screens.PasteModeScreen
import com.example.whisperremote.ui.screens.PermissionsScreen
import com.example.whisperremote.ui.screens.TranscriptionScreen
import com.example.whisperremote.ui.screens.WordsTriggersScreen
import com.example.whisperremote.ui.theme.WhisperTheme
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
}
