// This file provides the primary home settings screen for Whisper Unchained.
// It is called by MainActivity and hosts the One UI-style grouped navigation hub.
// Inputs: SettingsRepository state, connection state (health check results), flavor flags (BuildConfig.FEATURE_INTERNAL_AUDIO),
//         navigation callbacks to subpages (transcription, words/triggers, ai-editing, paste-mode, permissions, floating-button, about, intro).
// Outputs: Scrollable list with collapsing LargeTopAppBar, account-style StatusCard, grouped borderless rows,
//          colored circle icons on top-level rows only, and bottom "Looking for something else?" card.
// Reason for edit: Remove redundant Transcription row under StatusCard and group Words & Triggers with AI Editing.
// Reason for edit: Add backward-compatibility upgrade banner for old http:// pairings per contract 06 §3.5.

package com.example.whisperremote.ui.screens

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.whisperremote.BuildConfig
import com.example.whisperremote.FloatingButtonService
import com.example.whisperremote.R
import com.example.whisperremote.data.InjectionMode
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsNavRow
import com.example.whisperremote.ui.components.SettingsSwitchRow
import com.example.whisperremote.ui.components.StatusCard
import com.example.whisperremote.ui.components.StatusCardState
import com.example.whisperremote.ui.theme.WhisperTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: SettingsRepository,
    statusCardState: StatusCardState,
    onNavigateToTranscription: () -> Unit,
    onNavigateToWordsTriggers: () -> Unit,
    onNavigateToAiEditing: () -> Unit,
    onNavigateToPasteMode: () -> Unit,
    onNavigateToFloatingButton: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onLaunchIntro: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            FloatingButtonService.getInstance()?.setMediaProjectionData(result.resultCode, result.data!!)
        }
    }

    val aiEditingSubtitle = remember(repository.cleanupEnabled, repository.cleanupShorten, repository.cleanupTone) {
        if (!repository.cleanupEnabled) "Off"
        else "On · Tone: ${repository.cleanupTone.replaceFirstChar { it.uppercase() }}"
    }

    val pasteModeSubtitle = remember(repository.injectionMode) {
        if (repository.injectionMode == InjectionMode.ACCESSIBILITY) "Paste automatically"
        else "Clipboard chip"
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.whisper_remote),
                                contentDescription = "Logo",
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (BuildConfig.FLAVOR == "full") "Whisper Unchained Full" else "Whisper Unchained",
                                style = if (BuildConfig.FLAVOR == "full") MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
        ) {
            // Status Card
            item {
                StatusCard(
                    state = statusCardState,
                    onClick = {
                        if (statusCardState is StatusCardState.IncompleteSetup) {
                            if (repository.baseUrl.isBlank()) onNavigateToTranscription() else onNavigateToPermissions()
                        } else {
                            onNavigateToTranscription()
                        }
                    },
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                )
            }

            // Backward-compatibility banner for older http:// pairings per contract 06 §3.5
            val isOldHttpPairing = repository.transcriptionTarget == TranscriptionTarget.PC.id &&
                repository.baseUrl.isNotBlank() &&
                repository.baseUrl.startsWith("http://", ignoreCase = true)
            if (isOldHttpPairing) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Connection Encryption Available",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Your PC connection is now encrypted. Update Whisper Unchained Server on your PC, then scan its new pairing code.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onNavigateToTranscription,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Scan new code")
                            }
                        }
                    }
                }
            }

            // Group 1: Text modification (Words & Triggers + AI Editing)
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsNavRow(
                        title = "Words & Triggers",
                        subtitle = "Vocabulary, name corrections & prompts",
                        subtitleIsValue = false,
                        icon = Icons.Default.Edit,
                        iconBackgroundColor = Color(0xFF8A56E2),
                        onClick = onNavigateToWordsTriggers
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "AI Editing",
                        subtitle = aiEditingSubtitle,
                        subtitleIsValue = true,
                        icon = Icons.Default.Build,
                        iconBackgroundColor = WhisperTokens.BrandTeal,
                        onClick = onNavigateToAiEditing
                    )
                }
            }

            // Group 2: How it types & floats
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsNavRow(
                        title = "Paste into apps",
                        subtitle = pasteModeSubtitle,
                        subtitleIsValue = true,
                        icon = Icons.Default.Check,
                        iconBackgroundColor = Color(0xFFE57E3B),
                        onClick = onNavigateToPasteMode
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Floating button",
                        subtitle = "Overlay behavior & Tailscale auto-restart",
                        subtitleIsValue = false,
                        icon = Icons.Default.PlayArrow,
                        iconBackgroundColor = Color(0xFF29A6B8),
                        onClick = onNavigateToFloatingButton
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Permissions",
                        subtitle = "Microphone, overlay, accessibility & battery",
                        subtitleIsValue = false,
                        icon = Icons.Default.Lock,
                        iconBackgroundColor = Color(0xFF38A169),
                        onClick = onNavigateToPermissions
                    )
                }
            }

            // Group 4: Phone Audio (Full flavor only)
            if (BuildConfig.FEATURE_INTERNAL_AUDIO) {
                item {
                    SettingsGroup(
                        header = "Phone audio",
                        footer = "Note: Phone calls cannot be captured.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        SettingsNavRow(
                            title = "Transcribe audio playing on this phone",
                            subtitle = "Capture audio from videos and podcasts",
                            subtitleIsValue = false,
                            icon = Icons.Default.PlayArrow,
                            iconBackgroundColor = Color(0xFF4A6572),
                            onClick = {
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        )
                    }
                }
            }

            // Group 5: About
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsNavRow(
                        title = "About",
                        subtitle = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) · Privacy policy & source code",
                        subtitleIsValue = false,
                        icon = Icons.Default.Info,
                        iconBackgroundColor = Color(0xFF718096),
                        onClick = onNavigateToAbout
                    )
                }
            }

            // Bottom "Looking for something else?" card (contracts/04 §3.1)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Looking for something else?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Check system permissions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = onNavigateToPermissions)
                            .padding(vertical = 4.dp)
                    )
                    Text(
                        text = "Run setup intro again",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = onLaunchIntro)
                            .padding(vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
