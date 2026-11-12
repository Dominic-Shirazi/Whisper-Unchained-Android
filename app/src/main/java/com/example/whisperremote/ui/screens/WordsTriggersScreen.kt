// This file provides the Words & Triggers subpage for Whisper Unchained.
// It is called by HomeScreen.
// Inputs: SettingsRepository, ApiClient (for 2-way vocabulary sync).
// Outputs: Vocabulary hints with character budget (540 chars), server name-correction hints,
//          sync button, name corrections editor (local for cloud / read-only for PC), and voice edit trigger info.
// Conforms to contracts/04-look-and-feel.md §3.3 and Appendix v1 §6.
// Reason for edit: Hop to main looper for Toast and state in vocabulary sync per F-26.

package com.example.whisperremote.ui.screens

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperremote.ApiClient
import com.example.whisperremote.VocabularyResult
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsSwitchRow
import com.example.whisperremote.ui.theme.WhisperTokens
import com.example.whisperremote.util.VocabularySyncHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsTriggersScreen(
    repository: SettingsRepository,
    apiClient: ApiClient,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var promptHints by remember { mutableStateOf(repository.promptHints) }
    var autoTrimHints by remember { mutableStateOf(repository.autoTrimHints) }
    var localNameCorrections by remember { mutableStateOf(repository.localNameCorrections) }
    var isSyncing by remember { mutableStateOf(false) }
    var fromNameCorrectionsWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverNameCorrections by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Initial background fetch of vocabulary data for self-hosted PC
    LaunchedEffect(Unit) {
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED && repository.baseUrl.isNotBlank()) {
            apiClient.getVocabulary { result ->
                mainHandler.post {
                    if (result is VocabularyResult.Success) {
                        fromNameCorrectionsWords = result.data.fromNameCorrections
                        serverNameCorrections = result.data.nameCorrections
                    }
                }
            }
        }
    }

    // Character budget calculation (540 characters max recommended)
    val charBudgetMax = 540
    val currentLength = promptHints.length
    val isOverBudget = currentLength > charBudgetMax

    fun syncVocabulary() {
        if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
            Toast.makeText(context, "Vocabulary sync is available for Self-Hosted servers", Toast.LENGTH_SHORT).show()
            return
        }
        isSyncing = true
        apiClient.getVocabulary { result ->
            mainHandler.post {
                isSyncing = false
                when (result) {
                    is VocabularyResult.Success -> {
                        fromNameCorrectionsWords = result.data.fromNameCorrections
                        serverNameCorrections = result.data.nameCorrections
                        val currentWords = VocabularySyncHelper.parseWords(promptHints)
                        val merged = VocabularySyncHelper.mergeAndDeduplicate(result.data.vocabulary, currentWords)
                        val mergedString = VocabularySyncHelper.formatForDisplay(merged)
                        promptHints = mergedString
                        repository.promptHints = mergedString
                        Toast.makeText(context, "Vocabulary synced with PC (${merged.size} words)", Toast.LENGTH_SHORT).show()
                    }
                    is VocabularyResult.Error -> {
                        Toast.makeText(context, "Could not fetch vocabulary: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is VocabularyResult.NetworkError -> {
                        Toast.makeText(context, "Network error: ${result.error.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Words & Triggers") },
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
            // Group 1: Vocabulary Hints
            item {
                SettingsGroup(
                    header = "Vocabulary Hints",
                    footer = "Words and jargon to boost recognition accuracy across all audio.",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        OutlinedTextField(
                            value = promptHints,
                            onValueChange = {
                                promptHints = it
                                repository.promptHints = it
                            },
                            placeholder = { Text("e.g. Lesley, Kubernetes, Whisper Unchained") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$currentLength / $charBudgetMax chars",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.weight(1f))

                            if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                                Button(
                                    onClick = { syncVocabulary() },
                                    enabled = !isSyncing,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text("Sync with PC")
                                }
                            }
                        }

                        // From name corrections list on PC (contracts/01 §8 & F-24 item 1)
                        if (fromNameCorrectionsWords.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "From name corrections on your PC",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = fromNameCorrectionsWords.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Group 2: Name Corrections (Heard as -> Write as, contracts/04 §6 & F-24 item 1)
            item {
                if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                    SettingsGroup(
                        header = "Heard as → Write as",
                        footer = "Edit these in Settings on your PC",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            if (serverNameCorrections.isNotEmpty()) {
                                serverNameCorrections.entries.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        SettingsDivider(insetStart = false)
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = entry.key,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "→",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Text(
                                            text = entry.value,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "None configured",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    SettingsGroup(
                        header = "Heard as → Write as",
                        footer = "Write one replacement per line: Heard as: Write as",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            OutlinedTextField(
                                value = localNameCorrections,
                                onValueChange = {
                                    localNameCorrections = it
                                    repository.localNameCorrections = it
                                },
                                placeholder = { Text("Leslie: Lesley\nEmma: Ame") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 6
                            )
                        }
                    }
                }
            }

            // Group 2: Behavioral sanitation switch
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsSwitchRow(
                        title = "Remove the word list when Whisper reads it back",
                        subtitle = "Prevents trailing silence hint-hallucinations while keeping natural speech",
                        checked = autoTrimHints,
                        onCheckedChange = {
                            autoTrimHints = it
                            repository.autoTrimHints = it
                        }
                    )
                }
            }

            // Group 3: Voice Edit Triggers
            item {
                SettingsGroup(
                    header = "Voice Commands",
                    footer = "Say 'prompt AI' during recording to send your instructions directly to the AI model.",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Column {
                            Text(
                                text = "Active voice trigger: \"prompt AI\"",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Example: \"prompt AI, rewrite this as an email\" — executes the command and pastes the resulting text directly into your app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
