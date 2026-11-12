// This file provides the AI Editing settings subpage for Whisper Unchained.
// It is called by HomeScreen.
// Inputs: SettingsRepository (cleanupEnabled, cleanupPunctuation, cleanupRewrite, cleanupTone, cleanupShorten, cleanupEmoji).
// Outputs: Toggleable AI dictation cleanup with smart-default note and 5 style rows (Punctuation, Grammar, Tone, Conciseness, Emoji).
// Conforms to contracts/04-look-and-feel.md §3.3.

package com.example.whisperremote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsNavRow
import com.example.whisperremote.ui.components.SettingsSwitchRow
import com.example.whisperremote.ui.theme.WhisperTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiEditingScreen(
    repository: SettingsRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cleanupEnabled by remember { mutableStateOf(repository.cleanupEnabled) }
    var punctuation by remember { mutableStateOf(repository.cleanupPunctuation) }
    var rewrite by remember { mutableStateOf(repository.cleanupRewrite) }
    var tone by remember { mutableStateOf(repository.cleanupTone) }
    var shorten by remember { mutableStateOf(repository.cleanupShorten) }
    var emoji by remember { mutableStateOf(repository.cleanupEmoji) }

    // Dialog state
    var activeDialog by remember { mutableStateOf<String?>(null) }

    val smartDefaultNote = remember(repository.providerPreset, repository.cleanupUserSet) {
        if (repository.cleanupUserSet) {
            "Customized by you"
        } else if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
            "Following your PC's setting"
        } else if (repository.cleanupChatKey.isNotBlank()) {
            "On because an AI model is set up"
        } else {
            "Off: no AI model set up yet"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Editing") },
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
            // Group 1: Main Cleanup Toggle with smart-default note
            item {
                SettingsGroup(
                    footer = smartDefaultNote,
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsSwitchRow(
                        title = "AI dictation cleanup",
                        subtitle = if (cleanupEnabled) "Removes filler sounds, applies 'no wait, I mean...' corrections and fixes formatting" else "Raw transcription will be pasted directly",
                        checked = cleanupEnabled,
                        onCheckedChange = {
                            cleanupEnabled = it
                            repository.cleanupEnabled = it
                            repository.cleanupUserSet = true
                        }
                    )
                }
            }

            // Group 2: Style Defaults (Shown only when cleanup is enabled)
            item {
                AnimatedVisibility(visible = cleanupEnabled) {
                    SettingsGroup(
                        header = "Style defaults",
                        footer = "These defaults apply to all dictations unless overridden on the floating pill.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        SettingsNavRow(
                            title = "Punctuation",
                            subtitle = punctuation.replaceFirstChar { it.uppercase() },
                            onClick = { activeDialog = "punctuation" }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            title = "Grammar & flow",
                            subtitle = when (rewrite) {
                                "keep" -> "Keep words as spoken"
                                "fix" -> "Fix grammar & flow"
                                "polish" -> "Polish text"
                                else -> rewrite.replaceFirstChar { it.uppercase() }
                            },
                            onClick = { activeDialog = "rewrite" }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            title = "Tone",
                            subtitle = tone.replaceFirstChar { it.uppercase() },
                            onClick = { activeDialog = "tone" }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            title = "Conciseness",
                            subtitle = when (shorten) {
                                "off" -> "Full length"
                                "light" -> "Shorter"
                                "strong" -> "Shortest"
                                else -> shorten.replaceFirstChar { it.uppercase() }
                            },
                            onClick = { activeDialog = "shorten" }
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            title = "Emoji",
                            subtitle = emoji.replaceFirstChar { it.uppercase() },
                            onClick = { activeDialog = "emoji" }
                        )
                    }
                }
            }
        }
    }

    // Selection Dialogs
    when (activeDialog) {
        "punctuation" -> {
            SingleChoiceDialog(
                title = "Punctuation",
                options = listOf(
                    "standard" to "Standard (periods, commas, capitalization)",
                    "light" to "Light (commas and periods only)",
                    "minimal" to "Minimal (minimal punctuation)"
                ),
                selected = punctuation,
                onSelect = {
                    punctuation = it
                    repository.cleanupPunctuation = it
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        "rewrite" -> {
            SingleChoiceDialog(
                title = "Grammar & flow",
                options = listOf(
                    "fix" to "Fix grammar & flow (Recommended)",
                    "keep" to "Keep words as spoken",
                    "polish" to "Polish phrasing"
                ),
                selected = rewrite,
                onSelect = {
                    rewrite = it
                    repository.cleanupRewrite = it
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        "tone" -> {
            SingleChoiceDialog(
                title = "Tone",
                options = listOf(
                    "neutral" to "Neutral",
                    "professional" to "Professional",
                    "casual" to "Casual",
                    "friendly" to "Friendly",
                    "mean" to "Mean (sarcastic / dry humor)"
                ),
                selected = tone,
                onSelect = { selectedTone ->
                    tone = selectedTone
                    repository.cleanupTone = selectedTone
                    // Contract F-15: Selecting other tones auto-promotes grammar to 'fix' if 'keep' was active
                    if (selectedTone != "neutral" && selectedTone != "mean" && repository.cleanupRewrite == "keep") {
                        repository.cleanupRewrite = "fix"
                        rewrite = "fix"
                    }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        "shorten" -> {
            SingleChoiceDialog(
                title = "Conciseness",
                options = listOf(
                    "off" to "Full length (default)",
                    "light" to "Shorter (concise)",
                    "strong" to "Shortest (bullet points / minimal)"
                ),
                selected = shorten,
                onSelect = {
                    shorten = it
                    repository.cleanupShorten = it
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        "emoji" -> {
            SingleChoiceDialog(
                title = "Emoji",
                options = listOf(
                    "never" to "Never",
                    "sparingly" to "Sparingly",
                    "generously" to "Generously"
                ),
                selected = emoji,
                onSelect = {
                    emoji = it
                    repository.cleanupEmoji = it
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(key) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == key,
                            onClick = { onSelect(key) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(26.dp)
    )
}
