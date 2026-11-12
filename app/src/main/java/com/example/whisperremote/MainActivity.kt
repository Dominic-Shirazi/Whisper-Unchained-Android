// This file is the primary activity and settings coordinator for Whisper Unchained.
// Rebuilt in Jetpack Compose + Material 3 per contracts/04-look-and-feel.md version 2 (F-20, F-21, F-22).
// Reason for edit: Add OnDevice status card state support per contract 05 §2.3.
// Reason for edit: Support QR scan success callback for automatic intro advance, and fix setup steps remaining calculation.
// Inputs: User configurations, camera QR code scanning (Google Code Scanner), Android system permissions.
// Outputs: Complete Compose UI hosting HomeScreen, subpages (Transcription, Words/Triggers, AI Editing, Paste Mode,
//          Floating Button, Permissions, About), and the 7-page First-Run Intro Sandbox.
// Maintains 100% parity with SettingsRepository, keystore-encrypted credentials, and background services.

package com.example.whisperremote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.net.TailscaleManager
import com.example.whisperremote.util.OnDeviceModelManager
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {

    private lateinit var repository: SettingsRepository
    private lateinit var apiClient: ApiClient
    private lateinit var tailscaleManager: TailscaleManager

    private var statusCardState by mutableStateOf<StatusCardState>(StatusCardState.Disconnected("Checking connection..."))

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updateStatusCardState()
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            checkOverlayAndStartService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = SettingsRepository(this)
        apiClient = ApiClient(this)
        tailscaleManager = TailscaleManager(this)

        updateStatusCardState()

        setContent {
            WhisperTheme {
                MainAppNavHost(
                    repository = repository,
                    apiClient = apiClient,
                    tailscaleManager = tailscaleManager,
                    statusCardState = statusCardState,
                    onScanQrCode = { cb -> startQrCodeScan(cb) },
                    onRefreshStatus = { updateStatusCardState() }
                )
            }
        }

        checkInitialPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatusCardState()
    }

    private fun updateStatusCardState() {
        if (!repository.hasCompletedFirstRunChecklist) {
            val stepsLeft = calculateSetupStepsLeft()
            statusCardState = StatusCardState.IncompleteSetup(stepsLeft = stepsLeft, totalSteps = 4)
            return
        }

        // Reason for edit: Display OnDevice status on home status card per contract 05 §2.3
        if (repository.transcriptionTarget == TranscriptionTarget.ON_DEVICE.id) {
            val modelSpec = OnDeviceModelManager(this).availableModels[repository.selectedOnDeviceModelId]
            val name = modelSpec?.displayName ?: "Parakeet TDT 110M"
            statusCardState = StatusCardState.OnDevice(name)
            return
        }

        if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
            val providerName = when (repository.providerPreset) {
                ProviderPreset.GROQ -> "Groq"
                ProviderPreset.TOGETHER -> "Together AI"
                ProviderPreset.OPENAI -> "OpenAI"
                ProviderPreset.OPENROUTER -> "OpenRouter"
                ProviderPreset.CUSTOM -> "Custom"
                else -> "Cloud"
            }
            statusCardState = StatusCardState.Cloud(
                providerName = providerName,
                model = repository.modelName.ifBlank { "Default model" }
            )
            return
        }

        if (repository.baseUrl.isBlank()) {
            statusCardState = StatusCardState.Disconnected("No server endpoint configured")
            return
        }

        apiClient.checkHealth { health ->
            runOnUiThread {
                statusCardState = when (health) {
                    is HealthResult.Ok -> {
                        if (!health.model.isNullOrBlank()) {
                            repository.modelName = health.model
                        }
                        StatusCardState.Connected(
                            targetName = "Your PC",
                            model = health.model ?: repository.modelName.ifBlank { "Ready" }
                        )
                    }
                    is HealthResult.Paused -> {
                        val reason = health.reason.orEmpty()
                        StatusCardState.Paused(message = if (reason.isNotBlank()) reason else "Paused on your PC")
                    }
                    is HealthResult.ServiceError -> {
                        StatusCardState.Disconnected("Server error: ${health.message}")
                    }
                    is HealthResult.Unreachable -> {
                        StatusCardState.Disconnected("Can't reach server: is Tailscale on?")
                    }
                }
            }
        }
    }

    private var pendingQrSuccessCallback: (() -> Unit)? = null

    private fun calculateSetupStepsLeft(): Int {
        var stepsLeft = 4
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) stepsLeft--
        if (repository.isServerVerified || repository.baseUrl.isNotBlank()) stepsLeft--
        if (Settings.canDrawOverlays(this)) stepsLeft--
        if (DictationAccessibilityService.getInstance() != null || repository.injectionMode == com.example.whisperremote.data.InjectionMode.CLIPBOARD) stepsLeft--
        val actual = maxOf(0, stepsLeft)
        if (actual == 0) {
            repository.hasCompletedFirstRunChecklist = true
        }
        return actual
    }

    private fun startQrCodeScan(onSuccess: (() -> Unit)? = null) {
        pendingQrSuccessCallback = onSuccess
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (!rawValue.isNullOrBlank()) {
                    val ok = handleScannedQrPayload(rawValue)
                    if (ok) {
                        pendingQrSuccessCallback?.invoke()
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "QR scan cancelled or failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun handleScannedQrPayload(raw: String): Boolean {
        val payload = repository.parseAndValidateQrPayload(raw)
        return if (payload != null) {
            repository.applyQrPayload(payload)
            repository.isServerVerified = true
            updateStatusCardState()
            Toast.makeText(this, "Connected to ${payload.hostDisplay}!", Toast.LENGTH_SHORT).show()
            true
        } else {
            Toast.makeText(this, "Invalid pairing code: not a Whisper Unchained payload", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun checkInitialPermissions() {
        if (!repository.hasCompletedFirstRunChecklist) {
            // First run intro guides user through mic permission sandbox
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkOverlayAndStartService()
        }
    }

    private fun checkOverlayAndStartService() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Log.d("MainActivity", "Mic permission missing; requesting permission before starting service.")
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingButtonService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start FloatingButtonService", e)
            }
        }
    }
}

@Composable
private fun MainAppNavHost(
    repository: SettingsRepository,
    apiClient: ApiClient,
    tailscaleManager: TailscaleManager,
    statusCardState: StatusCardState,
    onScanQrCode: ((onSuccess: (() -> Unit)?) -> Unit),
    onRefreshStatus: () -> Unit
) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val startDestination = if (!repository.hasCompletedFirstRunChecklist) "intro" else "home"

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize()
    ) {
        composable("home") {
            HomeScreen(
                repository = repository,
                statusCardState = statusCardState,
                onNavigateToTranscription = { navController.navigate("transcription") },
                onNavigateToWordsTriggers = { navController.navigate("words_triggers") },
                onNavigateToAiEditing = { navController.navigate("ai_editing") },
                onNavigateToPasteMode = { navController.navigate("paste_mode") },
                onNavigateToFloatingButton = { navController.navigate("floating_button") },
                onNavigateToPermissions = { navController.navigate("permissions") },
                onNavigateToAbout = { navController.navigate("about") },
                onLaunchIntro = { navController.navigate("intro") }
            )
        }
        composable("transcription") {
            TranscriptionScreen(
                repository = repository,
                apiClient = apiClient,
                onScanQrCode = { onScanQrCode(null) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("words_triggers") {
            WordsTriggersScreen(
                repository = repository,
                apiClient = apiClient,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("ai_editing") {
            AiEditingScreen(
                repository = repository,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("paste_mode") {
            PasteModeScreen(
                repository = repository,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("floating_button") {
            FloatingButtonScreen(
                repository = repository,
                tailscaleManager = tailscaleManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("permissions") {
            PermissionsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("about") {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() },
                onLaunchIntro = { navController.navigate("intro") }
            )
        }
        composable("intro") {
            IntroScreen(
                repository = repository,
                apiClient = apiClient,
                onScanQrCode = onScanQrCode,
                onFinishIntro = {
                    navController.navigate("home") {
                        popUpTo("intro") { inclusive = true }
                    }
                }
            )
        }
    }
}
