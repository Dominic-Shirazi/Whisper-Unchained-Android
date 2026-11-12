// This file provides the Transcription settings subpage for Whisper Unchained.
// It is called by HomeScreen or the status card.
// Inputs: SettingsRepository, ApiClient (for testing connection & health checks), GoogleCodeScanner trigger.
// Outputs: Pairing-first layout (Scan QR primary button, immediate Test connection status, collapsed Manual setup).
// Conforms to contracts/04-look-and-feel.md §3.2 and appendix v1 §4 & §5.

package com.example.whisperremote.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.whisperremote.ApiClient
import com.example.whisperremote.HealthResult
import com.example.whisperremote.R
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.theme.WhisperTokens

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
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionResultText by remember { mutableStateOf<String?>(null) }
    var isConnectionSuccess by remember { mutableStateOf<Boolean?>(null) }

    // Manual setup expands automatically on cloud presets that require a key, or when user clicks
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
        ) {
            // 1. Scan Pairing Code Hero & Test Connection right under it (§3.2)
            item {
                SettingsGroup(
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
                }
            }

            // 2. Manual setup ⌄ (Collapsed by default once connected, §3.2 item 3)
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
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
                                text = "Manual setup",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isManualSetupExpanded) "Hide manual server and model fields" else "Configure provider, endpoint, or keys manually",
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

                            // Provider preset selection
                            Text(
                                text = "Provider",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            ProviderPreset.values().forEach { preset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedPreset = preset
                                            repository.providerPreset = preset
                                            if (preset == ProviderPreset.GROQ && repository.modelName.isBlank()) {
                                                repository.modelName = "whisper-large-v3-turbo"
                                                selectedModel = "whisper-large-v3-turbo"
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
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(preset.displayName, style = MaterialTheme.typography.bodyLarge)
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

                            Spacer(modifier = Modifier.height(16.dp))

                            // Model Cards / Picker (Appendix v1 §4)
                            Text(
                                text = "Model",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            when (selectedPreset) {
                                ProviderPreset.SELF_HOSTED -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(14.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = if (selectedModel.isNotBlank()) "Your PC runs $selectedModel" else "Your PC chooses the model",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "Change the model in Settings on your PC",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
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
                                ProviderPreset.OPENROUTER_OPENAI -> {
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
                                ProviderPreset.CUSTOM -> {
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

            // 3. Language model for edits (Cloud-only, §3.2 item 4)
            if (selectedPreset != ProviderPreset.SELF_HOSTED) {
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
    }
}
