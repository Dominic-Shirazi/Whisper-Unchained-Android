// This file provides the Transcription settings subpage for Whisper Unchained.
// It is called by HomeScreen, StatusCard, or MainAppNavHost.
// Inputs: SettingsRepository (stores transcription target, server URL, API keys, fallback preferences, downloaded model IDs),
//         ApiClient (runs connection health checks and tests endpoints),
//         onScanQrCode (triggers Google Code Scanner for PC pairing),
//         onNavigateBack (returns to Home).
// Outputs: Touch-accessible settings interface hosting:
//          1. Transcription target selector (On this phone / My PC / Cloud service) with fallback switch.
//          2. PC pairing hero card (QR scan, health test, expandable manual setup).
//          3. Cloud service provider configuration (Groq, Together AI, OpenAI, OpenRouter, Custom).
//          4. On-device model manager (download progress, Wi-Fi only toggle, SHA-256 verification, and deletion).
// Conforms to contracts/04-look-and-feel.md §3.2 and contract 05 §2.3 & §2.6.
// Reason for edit: Add transcription target selector, PC fallback toggle, and on-device model download/management per contract 05 §2.3 & §2.6.
// Reason for edit: Use ic_download vector icon for model download buttons per F-35.
// Reason for edit: Add backup offline model card and collapsed row at top of transcription settings per F-36 item 7.

package com.example.whisperremote.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.whisperremote.ApiClient
import com.example.whisperremote.HealthResult
import com.example.whisperremote.R
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsRadioRow
import com.example.whisperremote.ui.components.SettingsSwitchRow
import com.example.whisperremote.ui.theme.WhisperTokens
import com.example.whisperremote.util.ModelDownloadState
import com.example.whisperremote.util.OnDeviceModelManager
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionScreen(
    repository: SettingsRepository,
    apiClient: ApiClient,
    onScanQrCode: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val onDeviceModelManager = remember { OnDeviceModelManager(context) }

    // Transcription target state
    var currentTarget by remember {
        mutableStateOf(TranscriptionTarget.fromId(repository.transcriptionTarget))
    }
    var fallbackToPhone by remember {
        mutableStateOf(repository.fallbackToOnDevice)
    }

    // On-device models state
    var selectedModelId by remember {
        mutableStateOf(repository.selectedOnDeviceModelId)
    }
    var downloadWifiOnly by remember {
        mutableStateOf(repository.downloadWifiOnly)
    }
    val modelReadyMap = remember { mutableStateMapOf<String, Boolean>() }
    var downloadState by remember { mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle) }
    var activeCancelSignal by remember { mutableStateOf<AtomicBoolean?>(null) }
    var deleteConfirmModelId by remember { mutableStateOf<String?>(null) }
    var isBackupDismissed by remember { mutableStateOf(false) }

    fun refreshModelStatus() {
        Thread {
            for (model in onDeviceModelManager.availableModels.values) {
                val ready = onDeviceModelManager.isModelReady(model.id)
                mainHandler.post {
                    modelReadyMap[model.id] = ready
                }
            }
        }.start()
    }

    LaunchedEffect(Unit) {
        refreshModelStatus()
    }

    // PC Connection test state
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionResultText by remember { mutableStateOf<String?>(null) }
    var isConnectionSuccess by remember { mutableStateOf<Boolean?>(null) }

    // Manual setup expands automatically on cloud presets or blank URL
    var isManualSetupExpanded by remember {
        mutableStateOf(repository.providerPreset != ProviderPreset.SELF_HOSTED || repository.baseUrl.isBlank())
    }

    var showManualPasteDialog by remember { mutableStateOf(false) }
    var manualPasteText by remember { mutableStateOf("") }

    // State for local editing
    var serverUrl by remember { mutableStateOf(repository.baseUrl) }
    var apiKey by remember { mutableStateOf(repository.apiKey) }
    var selectedPreset by remember { mutableStateOf(repository.providerPreset) }
    var selectedModel by remember { mutableStateOf(repository.modelName) }

    // Chat completion state
    var chatUrl by remember { mutableStateOf(repository.cleanupChatUrl) }
    var chatKey by remember { mutableStateOf(repository.cleanupChatKey) }
    var chatModel by remember { mutableStateOf(repository.cleanupChatModel) }

    fun testConnection() {
        isTestingConnection = true
        connectionResultText = "Testing connection..."
        isConnectionSuccess = null

        apiClient.checkHealth { health ->
            mainHandler.post {
                isTestingConnection = false
                when (health) {
                    is HealthResult.Ok -> {
                        isConnectionSuccess = true
                        connectionResultText = if (!health.model.isNullOrBlank()) {
                            repository.modelName = health.model
                            selectedModel = health.model
                            "Connected: your PC runs ${health.model}"
                        } else {
                            "Connected successfully"
                        }
                    }
                    is HealthResult.Paused -> {
                        isConnectionSuccess = true
                        val reason = health.reason.orEmpty()
                        connectionResultText = "Paused on your PC (${if (reason.isNotBlank()) reason else "Ready to resume"})"
                    }
                    is HealthResult.ServiceError -> {
                        isConnectionSuccess = false
                        connectionResultText = "Server error: ${health.message}"
                    }
                    is HealthResult.Unreachable -> {
                        isConnectionSuccess = false
                        connectionResultText = "Can't reach server: is Tailscale on or PC awake?"
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcription") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
        ) {
            // Backup model card / small row per F-36 item 7
            val isDefaultModelDownloaded = modelReadyMap[OnDeviceModelManager.DEFAULT_MODEL_ID] == true
            if (currentTarget != TranscriptionTarget.ON_DEVICE && !isDefaultModelDownloaded) {
                if (!isBackupDismissed) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Backup for when your PC can't be reached",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Download the 136 MB speech model and dictation keeps working offline, without AI editing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val isDl = downloadState is ModelDownloadState.Downloading && selectedModelId == OnDeviceModelManager.DEFAULT_MODEL_ID
                                if (isDl) {
                                    val dl = downloadState as ModelDownloadState.Downloading
                                    val dlMb = (dl.totalBytesDownloaded / 1_000_000L).toInt()
                                    val totalMb = 136
                                    val progress = if (dl.totalBytesExpected > 0) dl.totalBytesDownloaded.toFloat() / dl.totalBytesExpected.toFloat() else 0f
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Downloading $dlMb of $totalMb MB...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { isBackupDismissed = true }) {
                                            Text("Not now")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                selectedModelId = OnDeviceModelManager.DEFAULT_MODEL_ID
                                                val cancelSig = AtomicBoolean(false)
                                                activeCancelSignal = cancelSig
                                                Thread {
                                                    onDeviceModelManager.downloadModel(
                                                        modelId = OnDeviceModelManager.DEFAULT_MODEL_ID,
                                                        allowMobileData = !downloadWifiOnly,
                                                        cancelSignal = cancelSig
                                                    ) { state ->
                                                        mainHandler.post {
                                                            downloadState = state
                                                            if (state is ModelDownloadState.Ready) {
                                                                modelReadyMap[OnDeviceModelManager.DEFAULT_MODEL_ID] = true
                                                                fallbackToPhone = true
                                                                repository.fallbackToOnDevice = true
                                                                activeCancelSignal = null
                                                                refreshModelStatus()
                                                            } else if (state is ModelDownloadState.Failed || state is ModelDownloadState.Cancelled) {
                                                                activeCancelSignal = null
                                                            }
                                                        }
                                                    }
                                                }.start()
                                            }
                                        ) {
                                            Text("Download")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Offline backup model (136 MB)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Dictation keeps working if PC is unreachable",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { isBackupDismissed = false }
                                ) {
                                    Text("Get backup")
                                }
                            }
                        }
                    }
                }
            }

            // 1. Where your voice is transcribed (§2.3)
            item {
                SettingsGroup(
                    header = "Where your voice is transcribed",
                    footer = "Choose where speech recognition runs. When paired with your PC, on-device fallback keeps dictation working even if your computer is asleep or offline.",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    // Option 1: On this phone
                    SettingsRadioRow(
                        title = TranscriptionTarget.ON_DEVICE.displayName,
                        subtitle = "Offline, private, fast (Parakeet 110M)",
                        selected = currentTarget == TranscriptionTarget.ON_DEVICE,
                        onClick = {
                            currentTarget = TranscriptionTarget.ON_DEVICE
                            repository.transcriptionTarget = TranscriptionTarget.ON_DEVICE.id
                        }
                    )

                    SettingsDivider(insetStart = false)

                    // Option 2: My PC
                    SettingsRadioRow(
                        title = TranscriptionTarget.PC.displayName,
                        subtitle = "Your computer running Whisper (best accuracy)",
                        selected = currentTarget == TranscriptionTarget.PC,
                        onClick = {
                            currentTarget = TranscriptionTarget.PC
                            repository.transcriptionTarget = TranscriptionTarget.PC.id
                        }
                    )

                    SettingsDivider(insetStart = false)

                    // Option 3: Cloud service
                    SettingsRadioRow(
                        title = TranscriptionTarget.CLOUD.displayName,
                        subtitle = "External API (Groq, Together, OpenAI, etc.)",
                        selected = currentTarget == TranscriptionTarget.CLOUD,
                        onClick = {
                            currentTarget = TranscriptionTarget.CLOUD
                            repository.transcriptionTarget = TranscriptionTarget.CLOUD.id
                        }
                    )

                    // Fallback to on-device switch (visible when PC or Cloud is chosen)
                    if (currentTarget != TranscriptionTarget.ON_DEVICE) {
                        SettingsDivider(insetStart = false)
                        SettingsSwitchRow(
                            title = "Use this phone when PC can't be reached",
                            subtitle = "Transcribe locally if your computer is sleeping or unreachable, so your dictation is never lost.",
                            checked = fallbackToPhone,
                            onCheckedChange = {
                                fallbackToPhone = it
                                repository.fallbackToOnDevice = it
                            }
                        )
                    }
                }
            }

            // 2. Target Details Card
            when (currentTarget) {
                TranscriptionTarget.ON_DEVICE -> {
                    item {
                        SettingsGroup(
                            header = "On-device processing",
                            modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        tint = WhisperTokens.BrandTeal,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Private and offline",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                val hasCloudAi = repository.cleanupChatUrl.isNotBlank() && repository.cleanupChatKey.isNotBlank()
                                val privacyBody = "Speech is recognized on this phone; your audio never leaves it." +
                                        if (hasCloudAi) " (If you turn on AI Editing with a cloud service, the text is sent there for cleanup.)" else ""
                                Text(
                                    text = privacyBody,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                TranscriptionTarget.PC -> {
                    // Pair with your computer (§3.2)
                    item {
                        SettingsGroup(
                            header = "Computer connection",
                            modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = "Pair with your computer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Open Settings on your PC → Phone → Show pairing QR",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                // Primary Button: Scan QR
                                Button(
                                    onClick = onScanQrCode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(25.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan pairing code", style = MaterialTheme.typography.labelLarge)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Test Connection right under it (§3.2 item 2)
                                OutlinedButton(
                                    onClick = { testConnection() },
                                    enabled = !isTestingConnection,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    if (isTestingConnection) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Test connection")
                                }

                                // Plain result line (color-blind safe: text + icon)
                                if (!connectionResultText.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        val statusColor = when (isConnectionSuccess) {
                                            true -> WhisperTokens.BrandTeal
                                            false -> Color(0xFFD9694A)
                                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(
                                            text = if (isConnectionSuccess == true) "✓ " else if (isConnectionSuccess == false) "⚠ " else "• ",
                                            color = statusColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = connectionResultText!!,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = statusColor
                                        )
                                    }
                                }
                            }

                            // Manual setup collapsible inside PC group
                            SettingsDivider(insetStart = false)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) {
                                        isManualSetupExpanded = !isManualSetupExpanded
                                    }
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Manual connection details",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isManualSetupExpanded) "Hide manual server and model fields" else "Configure endpoint URL or paste code manually",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (isManualSetupExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(visible = isManualSetupExpanded) {
                                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    SettingsDivider(insetStart = false)
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Server endpoint
                                    OutlinedTextField(
                                        value = serverUrl,
                                        onValueChange = {
                                            serverUrl = it
                                            repository.baseUrl = it
                                        },
                                        label = { Text("Server endpoint (/v1)") },
                                        placeholder = { Text("http://100.x.y.z:5000/v1") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // API Key
                                    OutlinedTextField(
                                        value = apiKey,
                                        onValueChange = {
                                            apiKey = it
                                            repository.apiKey = it
                                        },
                                        label = { Text("API Key (optional for self-hosted)") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Paste pairing code manually button
                                    Text(
                                        text = "Paste code manually",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { showManualPasteDialog = true }
                                            .padding(vertical = 6.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }

                TranscriptionTarget.CLOUD -> {
                    // Cloud service configuration
                    item {
                        SettingsGroup(
                            header = "Cloud provider",
                            footer = "Whisper Unchained connects directly to your own API account. Audio is never stored by this app.",
                            modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                var infoDialogPreset by remember { mutableStateOf<ProviderPreset?>(null) }

                                if (infoDialogPreset != null) {
                                    val p = infoDialogPreset!!
                                    AlertDialog(
                                        onDismissRequest = { infoDialogPreset = null },
                                        title = {
                                            Text(
                                                text = p.displayName,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        text = {
                                            Column {
                                                Text(
                                                    text = "PRIVACY",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = p.privacySummary,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )

                                                Spacer(modifier = Modifier.height(12.dp))

                                                Text(
                                                    text = "PRICING",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = p.pricingSummary,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )

                                                Spacer(modifier = Modifier.height(12.dp))

                                                Text(
                                                    text = "SPEED",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = p.speedSummary,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )

                                                Spacer(modifier = Modifier.height(16.dp))

                                                Text(
                                                    text = "Pricing estimates as of November 2026. Billed directly by each provider through your personal API account. Whisper Unchained does not process payments or collect fees.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )

                                                if (p.pricingUrl != null) {
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(
                                                        text = "View official pricing →",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.clickable {
                                                            try {
                                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.pricingUrl)))
                                                            } catch (_: Exception) {}
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(onClick = { infoDialogPreset = null }) {
                                                Text("Got it")
                                            }
                                        }
                                    )
                                }

                                // List of Cloud Presets (exclude SELF_HOSTED here since it's under My PC)
                                ProviderPreset.entries.filter { it != ProviderPreset.SELF_HOSTED }.forEach { preset ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedPreset = preset
                                                repository.providerPreset = preset
                                                if (preset.defaultBaseUrl.isNotBlank()) {
                                                    serverUrl = preset.defaultBaseUrl
                                                    repository.baseUrl = preset.defaultBaseUrl
                                                }
                                                if (preset.defaultModel.isNotBlank()) {
                                                    selectedModel = preset.defaultModel
                                                    repository.modelName = preset.defaultModel
                                                }
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedPreset == preset,
                                            onClick = {
                                                selectedPreset = preset
                                                repository.providerPreset = preset
                                                if (preset.defaultBaseUrl.isNotBlank()) {
                                                    serverUrl = preset.defaultBaseUrl
                                                    repository.baseUrl = preset.defaultBaseUrl
                                                }
                                                if (preset.defaultModel.isNotBlank()) {
                                                    selectedModel = preset.defaultModel
                                                    repository.modelName = preset.defaultModel
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(preset.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                            Text(
                                                text = preset.tagLine,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { infoDialogPreset = preset },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = "${preset.displayName} info",
                                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Server endpoint
                                OutlinedTextField(
                                    value = serverUrl,
                                    onValueChange = {
                                        serverUrl = it
                                        repository.baseUrl = it
                                    },
                                    label = { Text("API Endpoint (/v1)") },
                                    placeholder = { Text("https://api.groq.com/openai/v1") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // API Key
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = {
                                        apiKey = it
                                        repository.apiKey = it
                                    },
                                    label = { Text("API Key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // Model Selection
                                Text(
                                    text = "Model",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                when (selectedPreset) {
                                    ProviderPreset.GROQ -> {
                                        val groqModels = listOf(
                                            "whisper-large-v3-turbo" to "Recommended · Ultra-fast, low cost",
                                            "whisper-large-v3" to "Most accurate"
                                        )
                                        groqModels.forEach { (model, desc) ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = selectedModel == model,
                                                    onClick = {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(model, style = MaterialTheme.typography.bodyLarge)
                                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    ProviderPreset.TOGETHER -> {
                                        val togetherModels = listOf(
                                            "whisper-large-v3" to "Standard high accuracy",
                                            "whisper-large-v3-turbo" to "Fast & low cost"
                                        )
                                        togetherModels.forEach { (model, desc) ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = selectedModel == model,
                                                    onClick = {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(model, style = MaterialTheme.typography.bodyLarge)
                                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    ProviderPreset.OPENAI -> {
                                        val openAiModels = listOf(
                                            "whisper-1" to "Standard Whisper",
                                            "gpt-4o-mini-transcribe" to "Fast multi-modal",
                                            "gpt-4o-transcribe" to "High accuracy"
                                        )
                                        openAiModels.forEach { (model, desc) ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = selectedModel == model,
                                                    onClick = {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(model, style = MaterialTheme.typography.bodyLarge)
                                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    ProviderPreset.OPENROUTER -> {
                                        val openRouterModels = listOf(
                                            "openai/whisper-large-v3" to "Whisper Large via Router",
                                            "openai/whisper-1" to "Whisper 1"
                                        )
                                        openRouterModels.forEach { (model, desc) ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = selectedModel == model,
                                                    onClick = {
                                                        selectedModel = model
                                                        repository.modelName = model
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(model, style = MaterialTheme.typography.bodyLarge)
                                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        OutlinedTextField(
                                            value = selectedModel,
                                            onValueChange = {
                                                selectedModel = it
                                                repository.modelName = it
                                            },
                                            label = { Text("Model name") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Test Connection Button for Cloud
                                OutlinedButton(
                                    onClick = { testConnection() },
                                    enabled = !isTestingConnection,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    if (isTestingConnection) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Test API connection")
                                }

                                if (!connectionResultText.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        val statusColor = when (isConnectionSuccess) {
                                            true -> WhisperTokens.BrandTeal
                                            false -> Color(0xFFD9694A)
                                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(
                                            text = if (isConnectionSuccess == true) "✓ " else if (isConnectionSuccess == false) "⚠ " else "• ",
                                            color = statusColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = connectionResultText!!,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = statusColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Language model for edits (Cloud-only, §3.2 item 4)
                    item {
                        SettingsGroup(
                            header = "Language model for edits",
                            footer = "Used for 'prompt AI' voice commands when using cloud transcription.",
                            modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                OutlinedTextField(
                                    value = chatUrl,
                                    onValueChange = {
                                        chatUrl = it
                                        repository.cleanupChatUrl = it
                                    },
                                    label = { Text("Chat endpoint (/v1)") },
                                    placeholder = { Text("https://api.groq.com/openai/v1") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = chatKey,
                                    onValueChange = {
                                        chatKey = it
                                        repository.cleanupChatKey = it
                                    },
                                    label = { Text("Chat API Key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = chatModel,
                                    onValueChange = {
                                        chatModel = it
                                        repository.cleanupChatModel = it
                                    },
                                    label = { Text("Chat model name") },
                                    placeholder = { Text("llama-3.3-70b-versatile") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }

            // 3. On-Device Speech Model Section (Contract 05 §2.6)
            item {
                SettingsGroup(
                    header = "On-device speech model",
                    footer = "Models run 100% offline using sherpa-onnx and Silero VAD. Parakeet weights are licensed under CC-BY-4.0 by NVIDIA.",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    // Wi-Fi only toggle
                    SettingsSwitchRow(
                        title = "Download over Wi-Fi only",
                        subtitle = "Turn off to allow downloading models on mobile data",
                        checked = downloadWifiOnly,
                        onCheckedChange = {
                            downloadWifiOnly = it
                            repository.downloadWifiOnly = it
                        }
                    )

                    SettingsDivider(insetStart = false)

                    // Model list
                    onDeviceModelManager.availableModels.values.forEachIndexed { index, modelSpec ->
                        val isReady = modelReadyMap[modelSpec.id] == true
                        val isDownloadingThis = downloadState is ModelDownloadState.Downloading &&
                                (downloadState as ModelDownloadState.Downloading).modelId == modelSpec.id
                        val isVerifyingThis = downloadState is ModelDownloadState.Verifying

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedModelId == modelSpec.id,
                                    onClick = {
                                        selectedModelId = modelSpec.id
                                        repository.selectedOnDeviceModelId = modelSpec.id
                                    },
                                    enabled = isReady
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = modelSpec.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = modelSpec.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isReady) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Installed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WhisperTokens.BrandTeal,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { deleteConfirmModelId = modelSpec.id },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete model",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Download action on its own line below model details per F-32 item 4
                            if (!isReady && !isDownloadingThis && !isVerifyingThis) {
                                val sizeMb = when (modelSpec.id) {
                                    OnDeviceModelManager.DEFAULT_MODEL_ID -> 136
                                    OnDeviceModelManager.ACCURACY_MODEL_ID -> 661
                                    OnDeviceModelManager.MULTI_MODEL_ID -> 671
                                    else -> (modelSpec.totalBytes / 1_000_000L).toInt()
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 48.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (downloadWifiOnly && !onDeviceModelManager.isWifiConnected()) {
                                                Toast.makeText(
                                                    context,
                                                    "Wi-Fi connection required. Turn off 'Download over Wi-Fi only' to download on mobile data.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                return@Button
                                            }

                                            val cancelSig = AtomicBoolean(false)
                                            activeCancelSignal = cancelSig

                                            Thread {
                                                onDeviceModelManager.downloadModel(
                                                    modelId = modelSpec.id,
                                                    allowMobileData = !downloadWifiOnly,
                                                    cancelSignal = cancelSig
                                                ) { state ->
                                                    mainHandler.post {
                                                        downloadState = state
                                                        when (state) {
                                                            is ModelDownloadState.Ready -> {
                                                                refreshModelStatus()
                                                                selectedModelId = modelSpec.id
                                                                repository.selectedOnDeviceModelId = modelSpec.id
                                                                activeCancelSignal = null
                                                                Toast.makeText(context, "${modelSpec.displayName} ready for offline use", Toast.LENGTH_SHORT).show()
                                                            }
                                                            is ModelDownloadState.Failed -> {
                                                                activeCancelSignal = null
                                                                Toast.makeText(context, "Download failed: ${state.error}", Toast.LENGTH_LONG).show()
                                                            }
                                                            is ModelDownloadState.Cancelled -> {
                                                                activeCancelSignal = null
                                                            }
                                                            else -> {}
                                                        }
                                                    }
                                                }
                                            }.start()
                                        },
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(painter = painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Download ($sizeMb MB)", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            // Download progress / verification UI per contract 05 §2.6 & F-32
                            if (isDownloadingThis) {
                                val dlState = downloadState as ModelDownloadState.Downloading
                                val dlMb = (dlState.totalBytesDownloaded / 1_000_000L).toInt()
                                val totalMb = when (modelSpec.id) {
                                    OnDeviceModelManager.DEFAULT_MODEL_ID -> 136
                                    OnDeviceModelManager.ACCURACY_MODEL_ID -> 661
                                    OnDeviceModelManager.MULTI_MODEL_ID -> 671
                                    else -> (dlState.totalBytesExpected / 1_000_000L).toInt()
                                }
                                val progress = if (dlState.totalBytesExpected > 0) {
                                    dlState.totalBytesDownloaded.toFloat() / dlState.totalBytesExpected.toFloat()
                                } else 0f

                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Downloading $dlMb of $totalMb MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = { activeCancelSignal?.set(true) }
                                    ) {
                                        Text("Cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            } else if (isVerifyingThis && downloadState is ModelDownloadState.Verifying) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Verifying checksum (${(downloadState as ModelDownloadState.Verifying).fileName})...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (index < onDeviceModelManager.availableModels.size - 1) {
                            SettingsDivider(insetStart = false)
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (deleteConfirmModelId != null) {
        val modelToDelete = onDeviceModelManager.availableModels[deleteConfirmModelId]
        AlertDialog(
            onDismissRequest = { deleteConfirmModelId = null },
            title = { Text("Delete speech model?") },
            text = {
                Text("This will remove ${modelToDelete?.displayName ?: "this model"} from your phone to free up storage. You can re-download it anytime over Wi-Fi.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteConfirmModelId!!
                        deleteConfirmModelId = null
                        Thread {
                            onDeviceModelManager.deleteModel(id)
                            refreshModelStatus()
                            mainHandler.post {
                                Toast.makeText(context, "Model deleted", Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmModelId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Manual Paste Pairing Dialog
    if (showManualPasteDialog) {
        AlertDialog(
            onDismissRequest = {
                showManualPasteDialog = false
                manualPasteText = ""
            },
            title = { Text("Paste pairing code") },
            text = {
                Column {
                    Text(
                        text = "Paste the JSON pairing payload from your computer's terminal or settings.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualPasteText,
                        onValueChange = { manualPasteText = it },
                        label = { Text("JSON payload") },
                        placeholder = { Text("{\"v\": 1, \"base_url\": ...}") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ok = repository.importQrPayload(manualPasteText.trim())
                        if (ok) {
                            serverUrl = repository.baseUrl
                            apiKey = repository.apiKey
                            selectedModel = repository.modelName
                            selectedPreset = repository.providerPreset
                            showManualPasteDialog = false
                            manualPasteText = ""
                            testConnection()
                        } else {
                            Toast.makeText(context, "Invalid pairing code JSON", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showManualPasteDialog = false
                        manualPasteText = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
