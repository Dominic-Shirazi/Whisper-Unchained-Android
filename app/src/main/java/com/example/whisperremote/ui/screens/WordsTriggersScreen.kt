// This file provides the Words & Triggers screen for Whisper Unchained.
// It is called by HomeScreen navigation.
// Inputs: SettingsRepository, ApiClient for 2-way vocabulary and name corrections synchronization.
// Outputs: UI for editing helper words and name corrections with offline queueing, undo snackbars, and voice commands.
// Conforms to contracts/01-transcription-api.md §8 and F-43.
// Reason for edit: Center-align name correction arrows in a straight vertical column per user feedback.
// Reason for edit: Interactive Helper Words and Name Corrections editor with offline queueing per contract 01 §8 and F-43.

package com.example.whisperremote.ui.screens

import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperremote.ApiClient
import com.example.whisperremote.NameCorrectionsResult
import com.example.whisperremote.VocabularyResult
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.QueuedMutation
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsSwitchRow
import com.example.whisperremote.ui.theme.WhisperTokens
import com.example.whisperremote.util.TextRules
import com.example.whisperremote.util.VocabularySyncHelper
import com.example.whisperremote.util.WordRulesValidator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsTriggersScreen(
    repository: SettingsRepository,
    apiClient: ApiClient,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var autoTrimHints by remember { mutableStateOf(repository.autoTrimHints) }
    var isSyncing by remember { mutableStateOf(false) }

    // Cloud preset local corrections state
    var localPromptHints by remember { mutableStateOf(repository.promptHints) }
    var localCorrectionsMap by remember {
        mutableStateOf(TextRules.parseCorrectionsText(repository.localNameCorrections))
    }

    // Self-hosted server state
    var helperWords by remember {
        val initial = repository.cachedServerVocabulary.toList().ifEmpty {
            VocabularySyncHelper.parseWords(repository.promptHints)
        }
        mutableStateOf(initial)
    }
    var fromNameCorrectionsWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverNameCorrections by remember {
        mutableStateOf(repository.parseCachedServerNameCorrections())
    }
    var nameCorrectionsSupported by remember { mutableStateOf(true) }
    var charBudgetChars by remember { mutableIntStateOf(0) }
    val charBudgetMax = 540

    // Add field state for helper words
    var newWordInput by remember { mutableStateOf("") }
    var newWordError by remember { mutableStateOf<String?>(null) }

    // Dialog state
    var editingWordTarget by remember { mutableStateOf<String?>(null) }
    var showAddCorrectionDialog by remember { mutableStateOf(false) }
    var editingCorrectionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Initial background fetch of vocabulary data for self-hosted PC
    LaunchedEffect(Unit) {
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED && repository.baseUrl.isNotBlank()) {
            apiClient.replayPendingMutations()
            apiClient.getVocabulary { result ->
                mainHandler.post {
                    if (result is VocabularyResult.Success) {
                        helperWords = result.data.vocabulary
                        fromNameCorrectionsWords = result.data.fromNameCorrections
                        serverNameCorrections = result.data.nameCorrections
                        charBudgetChars = result.data.chars
                    }
                }
            }
        }
    }

    // Helper: update char budget estimate when local list changes
    fun updateCharEstimate() {
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
            val totalWords = helperWords + fromNameCorrectionsWords
            charBudgetChars = totalWords.joinToString(", ").length
        }
    }

    fun syncVocabulary() {
        if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
            Toast.makeText(context, "Vocabulary sync is available for Self-Hosted servers", Toast.LENGTH_SHORT).show()
            return
        }
        isSyncing = true
        apiClient.replayPendingMutations { _ ->
            apiClient.getVocabulary { result ->
                mainHandler.post {
                    isSyncing = false
                    when (result) {
                        is VocabularyResult.Success -> {
                            helperWords = result.data.vocabulary
                            fromNameCorrectionsWords = result.data.fromNameCorrections
                            serverNameCorrections = result.data.nameCorrections
                            charBudgetChars = result.data.chars
                            Toast.makeText(context, "Synced with PC (${helperWords.size} words)", Toast.LENGTH_SHORT).show()
                        }
                        is VocabularyResult.Error -> {
                            Toast.makeText(context, "Could not fetch vocabulary: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        is VocabularyResult.NetworkError -> {
                            Toast.makeText(context, "Could not reach PC: ${result.error.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Self-hosted Helper Word Actions
    fun onAddHelperWord(word: String) {
        val trimmed = word.trim()
        val error = WordRulesValidator.validateHelperWord(trimmed, existingWords = helperWords)
        if (error != null) {
            newWordError = error
            return
        }
        newWordError = null
        newWordInput = ""

        helperWords = helperWords + trimmed
        updateCharEstimate()

        val mutation = QueuedMutation(type = "vocab", addWords = listOf(trimmed))
        repository.queueWordMutation(mutation)

        apiClient.modifyVocabulary(add = listOf(trimmed)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                    charBudgetChars = result.data.chars
                }
                is VocabularyResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        helperWords = helperWords - trimmed
                        updateCharEstimate()
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is VocabularyResult.NetworkError -> {
                    // Stays queued with "waiting for your PC"
                }
            }
        }
    }

    fun onEditHelperWord(oldWord: String, newWord: String) {
        val trimmed = newWord.trim()
        if (trimmed.equals(oldWord, ignoreCase = false)) {
            editingWordTarget = null
            return
        }
        val error = WordRulesValidator.validateHelperWord(trimmed, existingWords = helperWords, currentWord = oldWord)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        editingWordTarget = null

        helperWords = helperWords.map { if (it.equals(oldWord, ignoreCase = true)) trimmed else it }
        updateCharEstimate()

        val mutation = QueuedMutation(type = "vocab", addWords = listOf(trimmed), removeWords = listOf(oldWord))
        repository.queueWordMutation(mutation)

        apiClient.modifyVocabulary(add = listOf(trimmed), remove = listOf(oldWord)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                    charBudgetChars = result.data.chars
                }
                is VocabularyResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        helperWords = helperWords.map { if (it.equals(trimmed, ignoreCase = true)) oldWord else it }
                        updateCharEstimate()
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is VocabularyResult.NetworkError -> {
                    // Stays queued
                }
            }
        }
    }

    fun onRemoveHelperWord(word: String) {
        helperWords = helperWords.filterNot { it.equals(word, ignoreCase = true) }
        updateCharEstimate()

        val mutation = QueuedMutation(type = "vocab", removeWords = listOf(word))
        repository.queueWordMutation(mutation)

        apiClient.modifyVocabulary(remove = listOf(word)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                    charBudgetChars = result.data.chars
                }
                is VocabularyResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                    }
                }
                is VocabularyResult.NetworkError -> {
                    // Stays queued
                }
            }
        }

        coroutineScope.launch {
            val snack = snackbarHostState.showSnackbar(
                message = "Removed \"$word\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (snack == SnackbarResult.ActionPerformed) {
                onAddHelperWord(word)
            }
        }
    }

    // Self-hosted Name Correction Actions
    fun onAddNameCorrection(heard: String, write: String) {
        val h = heard.trim()
        val w = write.trim()
        val error = WordRulesValidator.validateNameCorrection(h, w, existingHeardList = serverNameCorrections.keys)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        showAddCorrectionDialog = false

        val updated = serverNameCorrections.toMutableMap()
        updated[h] = w
        serverNameCorrections = updated

        val mutation = QueuedMutation(type = "name", setNameCorrections = mapOf(h to w))
        repository.queueWordMutation(mutation)

        apiClient.modifyNameCorrections(set = mapOf(h to w)) { result ->
            when (result) {
                is NameCorrectionsResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    serverNameCorrections = result.nameCorrections
                }
                is NameCorrectionsResult.Unsupported -> {
                    repository.removePendingMutation(mutation.id)
                    nameCorrectionsSupported = false
                    coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                }
                is NameCorrectionsResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        val reverted = serverNameCorrections.toMutableMap()
                        reverted.remove(h)
                        serverNameCorrections = reverted
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is NameCorrectionsResult.NetworkError -> {
                    // Stays queued
                }
            }
        }
    }

    fun onEditNameCorrection(oldHeard: String, newHeard: String, newWrite: String) {
        val h = newHeard.trim()
        val w = newWrite.trim()
        val error = WordRulesValidator.validateNameCorrection(h, w, existingHeardList = serverNameCorrections.keys, currentHeard = oldHeard)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        editingCorrectionTarget = null

        val updated = serverNameCorrections.toMutableMap()
        if (!h.equals(oldHeard, ignoreCase = true)) {
            updated.remove(oldHeard)
        }
        updated[h] = w
        serverNameCorrections = updated

        val removeList = if (!h.equals(oldHeard, ignoreCase = true)) listOf(oldHeard) else emptyList()
        val mutation = QueuedMutation(
            type = "name",
            setNameCorrections = mapOf(h to w),
            removeNameCorrections = removeList
        )
        repository.queueWordMutation(mutation)

        apiClient.modifyNameCorrections(set = mapOf(h to w), remove = removeList) { result ->
            when (result) {
                is NameCorrectionsResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    serverNameCorrections = result.nameCorrections
                }
                is NameCorrectionsResult.Unsupported -> {
                    repository.removePendingMutation(mutation.id)
                    nameCorrectionsSupported = false
                    coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                }
                is NameCorrectionsResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is NameCorrectionsResult.NetworkError -> {
                    // Stays queued
                }
            }
        }
    }

    fun onRemoveNameCorrection(heard: String, write: String) {
        val updated = serverNameCorrections.toMutableMap()
        updated.remove(heard)
        serverNameCorrections = updated

        val mutation = QueuedMutation(type = "name", removeNameCorrections = listOf(heard))
        repository.queueWordMutation(mutation)

        apiClient.modifyNameCorrections(remove = listOf(heard)) { result ->
            when (result) {
                is NameCorrectionsResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    serverNameCorrections = result.nameCorrections
                }
                is NameCorrectionsResult.Unsupported -> {
                    repository.removePendingMutation(mutation.id)
                    nameCorrectionsSupported = false
                }
                is NameCorrectionsResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                    }
                }
                is NameCorrectionsResult.NetworkError -> {
                    // Stays queued
                }
            }
        }

        coroutineScope.launch {
            val snack = snackbarHostState.showSnackbar(
                message = "Removed \"$heard → $write\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (snack == SnackbarResult.ActionPerformed) {
                onAddNameCorrection(heard, write)
            }
        }
    }

    // Cloud Preset Name Correction Actions
    fun onAddCloudCorrection(heard: String, write: String) {
        val h = heard.trim()
        val w = write.trim()
        val error = WordRulesValidator.validateNameCorrection(h, w, existingHeardList = localCorrectionsMap.keys)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        showAddCorrectionDialog = false
        val updated = localCorrectionsMap.toMutableMap()
        updated[h] = w
        localCorrectionsMap = updated
        repository.localNameCorrections = TextRules.formatCorrectionsText(updated)
    }

    fun onEditCloudCorrection(oldHeard: String, newHeard: String, newWrite: String) {
        val h = newHeard.trim()
        val w = newWrite.trim()
        val error = WordRulesValidator.validateNameCorrection(h, w, existingHeardList = localCorrectionsMap.keys, currentHeard = oldHeard)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        editingCorrectionTarget = null
        val updated = localCorrectionsMap.toMutableMap()
        if (!h.equals(oldHeard, ignoreCase = true)) {
            updated.remove(oldHeard)
        }
        updated[h] = w
        localCorrectionsMap = updated
        repository.localNameCorrections = TextRules.formatCorrectionsText(updated)
    }

    fun onRemoveCloudCorrection(heard: String, write: String) {
        val updated = localCorrectionsMap.toMutableMap()
        updated.remove(heard)
        localCorrectionsMap = updated
        repository.localNameCorrections = TextRules.formatCorrectionsText(updated)

        coroutineScope.launch {
            val snack = snackbarHostState.showSnackbar(
                message = "Removed \"$heard → $write\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (snack == SnackbarResult.ActionPerformed) {
                onAddCloudCorrection(heard, write)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // Group 1: Vocabulary Hints / Helper words
            item {
                if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                    SettingsGroup(
                        header = "Helper Words",
                        footer = "Words and jargon to boost recognition accuracy across all audio.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            // Inline add helper word
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newWordInput,
                                    onValueChange = {
                                        newWordInput = it
                                        newWordError = null
                                    },
                                    placeholder = { Text("Add helper word...") },
                                    singleLine = true,
                                    isError = newWordError != null,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onAddHelperWord(newWordInput) },
                                    enabled = newWordInput.isNotBlank(),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("Add")
                                }
                            }
                            if (newWordError != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = newWordError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Helper words list
                            if (helperWords.isNotEmpty()) {
                                helperWords.forEachIndexed { index, word ->
                                    if (index > 0) {
                                        SettingsDivider(insetStart = false)
                                    }
                                    val isQueued = repository.isVocabPending(word)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { editingWordTarget = word }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = word,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isQueued) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "waiting for your PC",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { onRemoveHelperWord(word) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove $word",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No helper words added yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Sync button & Budget row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isOver = charBudgetChars > charBudgetMax
                                Text(
                                    text = "$charBudgetChars / $charBudgetMax chars",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.weight(1f))

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

                            // From name corrections section
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
                } else {
                    // Cloud presets: Prompt hints text field
                    SettingsGroup(
                        header = "Vocabulary Hints",
                        footer = "Words and jargon to boost recognition accuracy across cloud audio.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            OutlinedTextField(
                                value = localPromptHints,
                                onValueChange = {
                                    localPromptHints = it
                                    repository.promptHints = it
                                },
                                placeholder = { Text("e.g. Lesley, Kubernetes, Whisper Unchained") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 6
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val isOver = localPromptHints.length > charBudgetMax
                            Text(
                                text = "${localPromptHints.length} / $charBudgetMax chars",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Group 2: Name Corrections (Heard as → Write as)
            item {
                if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                    val footerText = if (!nameCorrectionsSupported) {
                        "Update the PC app to edit these here"
                    } else {
                        "Tap a row to edit. Whisper audio will rewrite heard names to these spellings."
                    }
                    SettingsGroup(
                        header = "Name Corrections",
                        footer = footerText,
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            // Header row with "+ Add a correction" button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Heard as → Write as",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (nameCorrectionsSupported) {
                                    OutlinedButton(
                                        onClick = { showAddCorrectionDialog = true },
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add correction")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (serverNameCorrections.isNotEmpty()) {
                                serverNameCorrections.entries.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        SettingsDivider(insetStart = false)
                                    }
                                    val isQueued = repository.isNameCorrectionPending(entry.key)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = nameCorrectionsSupported) {
                                                editingCorrectionTarget = Pair(entry.key, entry.value)
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = entry.key,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Box(
                                                    modifier = Modifier.width(36.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "→",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                                Text(
                                                    text = entry.value,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            if (isQueued) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "waiting for your PC",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (nameCorrectionsSupported) {
                                            IconButton(
                                                onClick = { onRemoveNameCorrection(entry.key, entry.value) },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Remove correction",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No name corrections configured",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Cloud presets: Structured row editor for localNameCorrections per F-43 item 5
                    SettingsGroup(
                        header = "Name Corrections",
                        footer = "Tap a row to edit. Whisper audio will rewrite heard names to these spellings.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Heard as → Write as",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = { showAddCorrectionDialog = true },
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add correction")
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (localCorrectionsMap.isNotEmpty()) {
                                localCorrectionsMap.entries.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        SettingsDivider(insetStart = false)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                editingCorrectionTarget = Pair(entry.key, entry.value)
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = entry.key,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Box(
                                                modifier = Modifier.width(36.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "→",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            Text(
                                                text = entry.value,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        IconButton(
                                            onClick = { onRemoveCloudCorrection(entry.key, entry.value) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove correction",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No name corrections configured",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Group 3: Behavioral sanitation switch
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

            // Group 4: Voice Edit Triggers
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

    // Dialog: Edit Helper Word
    editingWordTarget?.let { targetWord ->
        var editedWordText by remember(targetWord) { mutableStateOf(targetWord) }
        var dialogError by remember(targetWord) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editingWordTarget = null },
            title = { Text("Edit Helper Word") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editedWordText,
                        onValueChange = {
                            editedWordText = it
                            dialogError = null
                        },
                        placeholder = { Text("Word or phrase") },
                        singleLine = true,
                        isError = dialogError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dialogError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dialogError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editedWordText.trim()
                        val err = WordRulesValidator.validateHelperWord(
                            trimmed,
                            existingWords = helperWords,
                            currentWord = targetWord
                        )
                        if (err != null) {
                            dialogError = err
                        } else {
                            onEditHelperWord(targetWord, trimmed)
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingWordTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Add Name Correction
    if (showAddCorrectionDialog) {
        var addHeard by remember { mutableStateOf("") }
        var addWrite by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddCorrectionDialog = false },
            title = { Text("Add Name Correction") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addHeard,
                        onValueChange = {
                            addHeard = it
                            addError = null
                        },
                        label = { Text("Heard as") },
                        placeholder = { Text("e.g. Corey") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addWrite,
                        onValueChange = {
                            addWrite = it
                            addError = null
                        },
                        label = { Text("Write as") },
                        placeholder = { Text("e.g. Cory") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = addError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val h = addHeard.trim()
                        val w = addWrite.trim()
                        val existing = if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                            serverNameCorrections.keys
                        } else {
                            localCorrectionsMap.keys
                        }
                        val err = WordRulesValidator.validateNameCorrection(h, w, existingHeardList = existing)
                        if (err != null) {
                            addError = err
                        } else {
                            if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                                onAddNameCorrection(h, w)
                            } else {
                                onAddCloudCorrection(h, w)
                            }
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCorrectionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Edit Name Correction
    editingCorrectionTarget?.let { (oldHeard, oldWrite) ->
        var editHeard by remember(oldHeard) { mutableStateOf(oldHeard) }
        var editWrite by remember(oldWrite) { mutableStateOf(oldWrite) }
        var editError by remember(oldHeard) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editingCorrectionTarget = null },
            title = { Text("Edit Name Correction") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editHeard,
                        onValueChange = {
                            editHeard = it
                            editError = null
                        },
                        label = { Text("Heard as") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editWrite,
                        onValueChange = {
                            editWrite = it
                            editError = null
                        },
                        label = { Text("Write as") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (editError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = editError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val h = editHeard.trim()
                        val w = editWrite.trim()
                        val existing = if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                            serverNameCorrections.keys
                        } else {
                            localCorrectionsMap.keys
                        }
                        val err = WordRulesValidator.validateNameCorrection(
                            h, w,
                            existingHeardList = existing,
                            currentHeard = oldHeard
                        )
                        if (err != null) {
                            editError = err
                        } else {
                            if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                                onEditNameCorrection(oldHeard, h, w)
                            } else {
                                onEditCloudCorrection(oldHeard, h, w)
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCorrectionTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
