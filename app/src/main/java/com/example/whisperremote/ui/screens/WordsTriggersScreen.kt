// This file provides the Words & Triggers screen for Whisper Unchained.
// It is called by HomeScreen navigation.
// Inputs: SettingsRepository, ApiClient for 2-way vocabulary, name corrections, and custom triggers synchronization.
// Outputs: UI for editing helper words, corrections & shortcuts, and custom triggers with library browser.
// Conforms to contracts/01-transcription-api.md §8, §11 and F-50, F-53.
// Reason for edit: Redesign LibraryBrowserDialog with pre-collapsed one-line skill rows, collapsible category headings with counts, search, "Small local models only" and dynamic category filters, 640dp max-width column, TalkBack descriptions, and saveable state per F-76.
// Reason for edit: Update "Triggers" / "Trigger Library" / "Custom triggers" user-visible wording to "Skills" / "Skill library" per F-62.
// Reason for edit: Add TabRow for Words & shortcuts and Custom triggers, rename Name corrections to Corrections & shortcuts with 200-char wrapping and rotating placeholders per F-50 and F-53.
// Reason for edit: Exhaustive handling of ParseError for vocabulary and name corrections per F-46.
// Reason for edit: Center-align name correction arrows in a straight vertical column per user feedback.
// Reason for edit: Interactive Helper Words and Name Corrections editor with offline queueing per contract 01 §8 and F-43.

package com.example.whisperremote.ui.screens

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.whisperremote.ApiClient
import com.example.whisperremote.CustomTriggersResult
import com.example.whisperremote.NameCorrectionsResult
import com.example.whisperremote.VocabularyResult
import com.example.whisperremote.data.CustomTrigger
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
    val charBudgetMax = 540

    // Computed "from name corrections" list: when live reply hasn't arrived, compute from cached serverNameCorrections values
    val effectiveFromNameCorrections = remember(fromNameCorrectionsWords, serverNameCorrections) {
        if (fromNameCorrectionsWords.isNotEmpty()) {
            fromNameCorrectionsWords
        } else {
            serverNameCorrections.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
    }

    // Computed total characters: length of ", ".join(distinct(vocabulary + those values))
    val charBudgetChars = remember(helperWords, effectiveFromNameCorrections) {
        val combined = (helperWords + effectiveFromNameCorrections).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (combined.isEmpty()) 0 else combined.joinToString(", ").length
    }

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
                    }
                }
            }
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
                            Toast.makeText(context, "Synced with PC (${helperWords.size} words)", Toast.LENGTH_SHORT).show()
                        }
                        is VocabularyResult.ParseError -> {
                            Toast.makeText(context, "Could not parse vocabulary: ${result.message}", Toast.LENGTH_SHORT).show()
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

        val mutation = QueuedMutation(type = "vocab", addWords = listOf(trimmed))
        repository.queueWordMutation(mutation)

        apiClient.modifyVocabulary(add = listOf(trimmed)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                }
                is VocabularyResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        helperWords = helperWords - trimmed
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is VocabularyResult.ParseError -> {
                    // Stays queued with "waiting for your PC"
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

        val mutation = QueuedMutation(type = "vocab", addWords = listOf(trimmed), removeWords = listOf(oldWord))
        repository.queueWordMutation(mutation)

        apiClient.modifyVocabulary(add = listOf(trimmed), remove = listOf(oldWord)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                }
                is VocabularyResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        helperWords = helperWords.map { if (it.equals(trimmed, ignoreCase = true)) oldWord else it }
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is VocabularyResult.ParseError -> {
                    // Stays queued with "waiting for your PC"
                }
                is VocabularyResult.NetworkError -> {
                    // Stays queued
                }
            }
        }
    }

    fun onRemoveHelperWord(word: String) {
        helperWords = helperWords.filterNot { it.equals(word, ignoreCase = true) }

        val mutation = QueuedMutation(type = "vocab", removeWords = listOf(word))
        repository.queueWordMutation(mutation)

        apiClient.modifyVocabulary(remove = listOf(word)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                }
                is VocabularyResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                    }
                }
                is VocabularyResult.ParseError -> {
                    // Stays queued with "waiting for your PC"
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
                is NameCorrectionsResult.ParseError -> {
                    // Stays queued with "waiting for your PC"
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
                is NameCorrectionsResult.ParseError -> {
                    // Stays queued with "waiting for your PC"
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
                is NameCorrectionsResult.ParseError -> {
                    // Stays queued with "waiting for your PC"
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

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            Column {
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
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Words & shortcuts") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Skills") }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        if (selectedTabIndex == 1) {
            CustomTriggersTab(
                repository = repository,
                apiClient = apiClient,
                snackbarHostState = snackbarHostState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
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
                        header = "Helper words",
                        footer = "Names and jargon Whisper should expect. Tap a word to change it.",
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
                            if (effectiveFromNameCorrections.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "From name corrections on your PC",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = effectiveFromNameCorrections.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    // Cloud presets: Prompt hints text field
                    SettingsGroup(
                        header = "Helper words",
                        footer = "Names and jargon Whisper should expect.",
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

            // Group 2: Corrections & shortcuts (When I say → Type this) per F-50
            item {
                if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                    val footerText = if (!nameCorrectionsSupported) {
                        "Update the PC app to edit these here"
                    } else {
                        "Fix a name Whisper gets wrong, or say a short phrase to type something long: a link, your email, your number."
                    }
                    SettingsGroup(
                        header = "Corrections & shortcuts",
                        footer = footerText,
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            // Header row with "+ Add correction" button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "When I say → Type this",
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
                                                    modifier = Modifier.weight(0.4f)
                                                )
                                                Box(
                                                    modifier = Modifier.width(28.dp),
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
                                                    modifier = Modifier.weight(0.6f)
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
                                    text = "No corrections configured",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Cloud presets: Structured row editor for localNameCorrections per F-43 item 5
                    SettingsGroup(
                        header = "Corrections & shortcuts",
                        footer = "Fix a name Whisper gets wrong, or say a short phrase to type something long: a link, your email, your number.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "When I say → Type this",
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
                                                modifier = Modifier.weight(0.4f)
                                            )
                                            Box(
                                                modifier = Modifier.width(28.dp),
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
                                                modifier = Modifier.weight(0.6f)
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
                                    text = "No corrections configured",
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

    // Dialog: Add Correction & Shortcut (per F-50 with rotating examples)
    if (showAddCorrectionDialog) {
        val examples = listOf(
            "Leslie" to "Lesley",
            "here's my link" to "here's my link: cyrus-studio.com",
            "my email address" to "name@example.com"
        )
        val exampleIndex = (serverNameCorrections.size + localCorrectionsMap.size) % examples.size
        val (placeholderHeard, placeholderWrite) = examples[exampleIndex]

        var addHeard by remember { mutableStateOf("") }
        var addWrite by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddCorrectionDialog = false },
            title = { Text("Add correction & shortcut") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addHeard,
                        onValueChange = {
                            addHeard = it
                            addError = null
                        },
                        label = { Text("When I say") },
                        placeholder = { Text(placeholderHeard) },
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
                        label = { Text("Type this (up to 200 chars)") },
                        placeholder = { Text(placeholderWrite) },
                        minLines = 2,
                        maxLines = 4,
                        singleLine = false,
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

    // Dialog: Edit Correction & Shortcut (per F-50)
    editingCorrectionTarget?.let { (oldHeard, oldWrite) ->
        var editHeard by remember(oldHeard) { mutableStateOf(oldHeard) }
        var editWrite by remember(oldWrite) { mutableStateOf(oldWrite) }
        var editError by remember(oldHeard) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editingCorrectionTarget = null },
            title = { Text("Edit correction & shortcut") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editHeard,
                        onValueChange = {
                            editHeard = it
                            editError = null
                        },
                        label = { Text("When I say") },
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
                        label = { Text("Type this (up to 200 chars)") },
                        minLines = 2,
                        maxLines = 4,
                        singleLine = false,
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

// Reason for edit: Custom triggers management tab with write-your-own and shared library browser per contract 01 §11 & F-53
@Composable
fun CustomTriggersTab(
    repository: SettingsRepository,
    apiClient: ApiClient,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var customTriggers by remember { mutableStateOf<List<CustomTrigger>>(emptyList()) }
    var triggersSupported by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    var showWriteOwnDialog by remember { mutableStateOf(false) }
    var showLibraryDialog by remember { mutableStateOf(false) }
    var editingTrigger by remember { mutableStateOf<CustomTrigger?>(null) }

    fun refreshTriggers() {
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
            isLoading = true
            apiClient.fetchCustomTriggers { res ->
                isLoading = false
                when (res) {
                    is CustomTriggersResult.Success -> {
                        customTriggers = res.triggers
                        triggersSupported = true
                    }
                    is CustomTriggersResult.Unsupported -> {
                        triggersSupported = false
                    }
                    is CustomTriggersResult.Error -> {
                        if (res.statusCode == 404) {
                            triggersSupported = false
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    LaunchedEffect(repository.providerPreset) {
        refreshTriggers()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
            item {
                SettingsGroup(
                    header = "Skills",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Skills only run through your PC for now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (!triggersSupported) {
            item {
                SettingsGroup(
                    header = "Skills",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Update the PC app to use skills",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Action buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showWriteOwnDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New skill")
                    }
                    OutlinedButton(
                        onClick = { showLibraryDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text("Browse the library")
                    }
                }
            }

            if (isLoading && customTriggers.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            } else if (customTriggers.isEmpty()) {
                item {
                    SettingsGroup(
                        header = "Your skills",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "None yet. Start with one from the library, or make your own.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(customTriggers, key = { it.id }) { trigger ->
                    SettingsGroup(modifier = Modifier.padding(bottom = 12.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = trigger.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = trigger.enabled,
                                    onCheckedChange = { isChecked ->
                                        val updated = trigger.copy(enabled = isChecked)
                                        val prev = customTriggers
                                        customTriggers = customTriggers.map { if (it.id == trigger.id) updated else it }
                                        apiClient.modifyCustomTriggers(set = listOf(updated)) { res ->
                                            if (res is CustomTriggersResult.Success) {
                                                customTriggers = res.triggers
                                            } else {
                                                customTriggers = prev
                                            }
                                        }
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Say: ${trigger.phrases.joinToString(" or ") { "\"$it\"" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            if (!trigger.description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = trigger.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { editingTrigger = trigger }) {
                                    Text("Edit")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = {
                                        val removed = trigger
                                        val prev = customTriggers
                                        customTriggers = customTriggers.filter { it.id != trigger.id }
                                        apiClient.modifyCustomTriggers(remove = listOf(trigger.id)) { res ->
                                            if (res is CustomTriggersResult.Success) {
                                                customTriggers = res.triggers
                                            }
                                        }
                                        coroutineScope.launch {
                                            val snack = snackbarHostState.showSnackbar(
                                                message = "Removed '${trigger.name}'",
                                                actionLabel = "Undo",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (snack == SnackbarResult.ActionPerformed) {
                                                apiClient.modifyCustomTriggers(set = listOf(removed)) { res ->
                                                    if (res is CustomTriggersResult.Success) {
                                                        customTriggers = res.triggers
                                                    } else {
                                                        customTriggers = prev
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("Remove", color = Color(0xFFD9694A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // New skill dialog
    if (showWriteOwnDialog) {
        TriggerEditorDialog(
            title = "New skill",
            initialTrigger = null,
            onDismiss = { showWriteOwnDialog = false },
            onSave = { trigger, callback ->
                apiClient.modifyCustomTriggers(set = listOf(trigger)) { res ->
                    when (res) {
                        is CustomTriggersResult.Success -> {
                            customTriggers = res.triggers
                            callback(null)
                            showWriteOwnDialog = false
                        }
                        is CustomTriggersResult.Error -> {
                            callback(res.message)
                        }
                        is CustomTriggersResult.NetworkError -> {
                            callback(res.error.message ?: "Network error")
                        }
                        else -> {
                            callback("Failed to save skill")
                        }
                    }
                }
            }
        )
    }

    // Edit dialog
    editingTrigger?.let { trig ->
        TriggerEditorDialog(
            title = "Edit skill",
            initialTrigger = trig,
            onDismiss = { editingTrigger = null },
            onSave = { updated, callback ->
                apiClient.modifyCustomTriggers(set = listOf(updated)) { res ->
                    when (res) {
                        is CustomTriggersResult.Success -> {
                            customTriggers = res.triggers
                            callback(null)
                            editingTrigger = null
                        }
                        is CustomTriggersResult.Error -> {
                            callback(res.message)
                        }
                        is CustomTriggersResult.NetworkError -> {
                            callback(res.error.message ?: "Network error")
                        }
                        else -> {
                            callback("Failed to update skill")
                        }
                    }
                }
            }
        )
    }

    // Browse library dialog
    if (showLibraryDialog) {
        LibraryBrowserDialog(
            apiClient = apiClient,
            userTriggers = customTriggers,
            onDismiss = { showLibraryDialog = false },
            onAddOrUpdate = { libTrigger ->
                apiClient.modifyCustomTriggers(set = listOf(libTrigger)) { res ->
                    if (res is CustomTriggersResult.Success) {
                        customTriggers = res.triggers
                    }
                }
            }
        )
    }
}

@Composable
fun TriggerEditorDialog(
    title: String,
    initialTrigger: CustomTrigger?,
    onDismiss: () -> Unit,
    onOpenLibraryTarget: ((String) -> Unit)? = null,
    onSave: (CustomTrigger, (error: String?) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(initialTrigger?.name ?: "") }
    var phrasesText by remember { mutableStateOf(initialTrigger?.phrases?.joinToString("\n") ?: "") }
    var prompt by remember { mutableStateOf(initialTrigger?.prompt ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val isNew = initialTrigger == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (isNew) {
                    Text(
                        text = "A skill is your own phrase for an instruction you've saved. Say the phrase and the AI follows that instruction, so you never explain it twice.\n• The phrase can be anything you like: \"prompt intake\", \"skill intake\", or your own words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { Text("Skill name (1-40 chars)") },
                    placeholder = { Text("e.g. Bug ticket") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = phrasesText,
                    onValueChange = { phrasesText = it; errorMessage = null },
                    label = { Text("What you'll say (1-6, one per line)") },
                    placeholder = { Text("e.g. prompt ticket\nskill ticket") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; errorMessage = null },
                    label = { Text("What the AI should do (10-6000 chars)") },
                    placeholder = { Text("Write it the way you'd explain the job to a person...") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty() || trimmedName.length > 40) {
                        errorMessage = "Name must be between 1 and 40 characters"
                        return@Button
                    }
                    val lines = phrasesText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isEmpty() || lines.size > 6) {
                        errorMessage = "Provide between 1 and 6 spoken phrases"
                        return@Button
                    }
                    for (p in lines) {
                        if (p.length < 2 || p.length > 60 || p.contains(",")) {
                            errorMessage = "Phrase '$p' must be 2-60 characters and cannot contain commas"
                            return@Button
                        }
                    }
                    val trimmedPrompt = prompt.trim()
                    if (trimmedPrompt.length < 10 || trimmedPrompt.length > 6000) {
                        errorMessage = "Instruction must be between 10 and 6000 characters"
                        return@Button
                    }

                    val id = initialTrigger?.id ?: trimmedName.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-').ifBlank { java.util.UUID.randomUUID().toString().take(8) }
                    val promptChanged = initialTrigger != null && trimmedPrompt != initialTrigger.prompt.trim()
                    val trigger = CustomTrigger(
                        id = id,
                        name = trimmedName,
                        phrases = lines,
                        prompt = trimmedPrompt,
                        enabled = initialTrigger?.enabled ?: true,
                        libraryId = if (promptChanged) null else initialTrigger?.libraryId,
                        libraryVersion = if (promptChanged) null else initialTrigger?.libraryVersion,
                        author = initialTrigger?.author,
                        temperature = initialTrigger?.temperature,
                        description = initialTrigger?.description,
                        category = initialTrigger?.category,
                        model = initialTrigger?.model
                    )

                    isSaving = true
                    onSave(trigger) { err ->
                        isSaving = false
                        if (err != null) {
                            errorMessage = err
                        }
                    }
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                } else {
                    Text(if (isNew) "Save skill" else "Done")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryBrowserDialog(
    apiClient: ApiClient,
    userTriggers: List<CustomTrigger>,
    targetId: String? = null,
    onDismiss: () -> Unit,
    onAddOrUpdate: (CustomTrigger) -> Unit,
    initialLibraryItems: List<CustomTrigger> = emptyList()
) {
    var libraryItems by remember { mutableStateOf(initialLibraryItems) }
    var isLoading by remember { mutableStateOf(initialLibraryItems.isEmpty()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Reason for edit: Saveable state for search, model filter, dynamic category filter, collapsed category headings, and expanded skills/instructions per F-76 (survives rotation & fold Tier 3)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var smallModelsOnly by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedSkillIds by rememberSaveable { mutableStateOf(if (targetId != null) listOf(targetId) else emptyList()) }
    var expandedInstructionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsedCategories by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        if (initialLibraryItems.isNotEmpty()) {
            return@LaunchedEffect
        }
        apiClient.fetchTriggerLibrary { list, err ->
            isLoading = false
            if (list != null && list.isNotEmpty()) {
                libraryItems = list
            } else {
                // Reason for edit: Fallback items including intake (Professions) and mean (Fun) per F-60, F-61 & F-62
                val fallbackList = listOf(
                    CustomTrigger(
                        id = "intake",
                        name = "Client intake sheet",
                        phrases = listOf("prompt intake", "client intake"),
                        prompt = "Format the details as an intake sheet: name, phone, email, address, and date of birth for each person, in the same order every time, with 'unknown' beside anything not provided.",
                        category = "Professions",
                        libraryId = "intake",
                        libraryVersion = 1,
                        description = "Turns messy client details into a neat intake sheet"
                    ),
                    CustomTrigger(
                        id = "mean",
                        name = "Mean mode",
                        phrases = listOf("prompt roast", "mean mode"),
                        prompt = "Rewrite this text with brutal sarcasm and witty mockery.",
                        category = "Fun",
                        libraryId = "mean",
                        libraryVersion = 1,
                        description = "Brutally roasts and mocks whatever you dictated"
                    )
                )
                libraryItems = fallbackList
                if (!err.isNullOrBlank()) {
                    errorMsg = null
                }
            }
        }
    }

    val allCategories = remember(libraryItems) {
        libraryItems.map { it.category?.ifBlank { "General" } ?: "General" }.distinct()
    }

    val filteredGrouped = remember(libraryItems, searchQuery, smallModelsOnly, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        val filtered = libraryItems.filter { item ->
            val cat = item.category?.ifBlank { "General" } ?: "General"
            val matchesModel = !smallModelsOnly || item.model != "strong"
            val matchesCategory = selectedCategory == null || cat == selectedCategory
            val matchesQuery = q.isEmpty() ||
                item.name.lowercase().contains(q) ||
                (item.description?.lowercase()?.contains(q) == true) ||
                item.phrases.any { it.lowercase().contains(q) } ||
                cat.lowercase().contains(q)
            matchesModel && matchesCategory && matchesQuery
        }
        filtered.groupBy { it.category?.ifBlank { "General" } ?: "General" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Skill library") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close skill library"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 640.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Skills other people have shared. Add one, then change its phrase or instruction to suit you.\nA skill is only instructions for your AI: read them before adding.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        // Search box
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search skills") },
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(14.dp)
                        )

                        // Filters: compact single scrollable row ("Small local models only" + "All" + dynamic categories from the file) so the list has maximum vertical space on phones
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "filter_small_models") {
                                FilterChip(
                                    selected = smallModelsOnly,
                                    onClick = { smallModelsOnly = !smallModelsOnly },
                                    label = { Text("Small local models only") }
                                )
                            }
                            if (allCategories.size > 1) {
                                item(key = "filter_all") {
                                    FilterChip(
                                        selected = selectedCategory == null,
                                        onClick = { selectedCategory = null },
                                        label = { Text("All") }
                                    )
                                }
                            }
                            items(allCategories, key = { "filter_cat_$it" }) { cat ->
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = {
                                        selectedCategory = if (selectedCategory == cat) null else cat
                                    },
                                    label = { Text(cat) }
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                            if (isLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (errorMsg != null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = errorMsg!!, color = MaterialTheme.colorScheme.error)
                                }
                            } else if (filteredGrouped.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "No skills match your search or filters.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (searchQuery.isNotEmpty() || smallModelsOnly || selectedCategory != null) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        TextButton(onClick = {
                                            searchQuery = ""
                                            smallModelsOnly = false
                                            selectedCategory = null
                                        }) {
                                            Text("Clear filters")
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    filteredGrouped.forEach { (category, items) ->
                                        val isCategoryCollapsed = category in collapsedCategories
                                        item(key = "cat_$category") {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        collapsedCategories = if (isCategoryCollapsed) {
                                                            collapsedCategories - category
                                                        } else {
                                                            collapsedCategories + category
                                                        }
                                                    }
                                                    .semantics {
                                                        contentDescription = if (isCategoryCollapsed) {
                                                            "$category, ${items.size} skills, collapsed, double tap to expand"
                                                        } else {
                                                            "$category, ${items.size} skills, expanded, double tap to collapse"
                                                        }
                                                    }
                                                    .padding(top = 12.dp, bottom = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "$category (${items.size})",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = if (isCategoryCollapsed) "▸" else "▾",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (!isCategoryCollapsed) {
                                            items(items, key = { it.id }) { item ->
                                                val userTrigger = userTriggers.firstOrNull { it.id == item.id || it.libraryId == item.id }
                                                val isAdded = userTrigger != null
                                                val needsUpdate = userTrigger != null && (item.libraryVersion ?: 1) > (userTrigger.libraryVersion ?: 1)
                                                val isSkillExpanded = item.id in expandedSkillIds
                                                val isInstructionExpanded = item.id in expandedInstructionIds
                                                val modelNote = if (item.model == "strong") "Needs a strong model" else "Works with small local models"
                                                val titleWithAdded = if (isAdded) "${item.name} ✓" else item.name
                                                val chevron = if (isSkillExpanded) "▾" else "▸"

                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp),
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                                            alpha = if (isSkillExpanded) 1.0f else 0.65f
                                                        )
                                                    )
                                                ) {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        // Collapsed/header row: tap opens or closes in place
                                                        BoxWithConstraints(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    expandedSkillIds = if (isSkillExpanded) {
                                                                        expandedSkillIds - item.id
                                                                    } else {
                                                                        expandedSkillIds + item.id
                                                                    }
                                                                }
                                                                .semantics(mergeDescendants = true) {
                                                                    contentDescription = if (isSkillExpanded) {
                                                                        "${item.name}, $modelNote, expanded, double tap to collapse"
                                                                    } else {
                                                                        "${item.name}, $modelNote, collapsed, double tap to expand"
                                                                    }
                                                                }
                                                                .padding(horizontal = 12.dp, vertical = 11.dp)
                                                        ) {
                                                            val placeNoteBeside = maxWidth >= 380.dp && titleWithAdded.length <= 26
                                                            if (placeNoteBeside) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Row(
                                                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        Text(
                                                                            text = "$titleWithAdded $chevron",
                                                                            style = MaterialTheme.typography.bodyLarge,
                                                                            fontWeight = FontWeight.SemiBold,
                                                                            color = MaterialTheme.colorScheme.onSurface
                                                                        )
                                                                    }
                                                                    Text(
                                                                        text = modelNote,
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }
                                                            } else {
                                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                                    Text(
                                                                        text = "$titleWithAdded $chevron",
                                                                        style = MaterialTheme.typography.bodyLarge,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        color = MaterialTheme.colorScheme.onSurface
                                                                    )
                                                                    Spacer(modifier = Modifier.height(2.dp))
                                                                    Text(
                                                                        text = modelNote,
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Expanded details: description, Say: "...", Show the instruction, Add / ✓ Added / Update
                                                        if (isSkillExpanded) {
                                                            HorizontalDivider(
                                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                                modifier = Modifier.padding(horizontal = 12.dp)
                                                            )
                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                                                            ) {
                                                                if (!item.description.isNullOrBlank()) {
                                                                    Text(
                                                                        text = item.description,
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                    Spacer(modifier = Modifier.height(4.dp))
                                                                }
                                                                Text(
                                                                    text = "Say: ${item.phrases.joinToString(" or ") { "\"$it\"" }}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.primary
                                                                )
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = if (isInstructionExpanded) "Hide the instruction ▾" else "Show the instruction ▸",
                                                                        style = MaterialTheme.typography.labelMedium,
                                                                        color = MaterialTheme.colorScheme.primary,
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            .padding(end = 8.dp)
                                                                            .clickable {
                                                                                expandedInstructionIds = if (isInstructionExpanded) {
                                                                                    expandedInstructionIds - item.id
                                                                                } else {
                                                                                    expandedInstructionIds + item.id
                                                                                }
                                                                            }
                                                                    )
                                                                    if (!isAdded) {
                                                                        Button(
                                                                            onClick = { onAddOrUpdate(item) },
                                                                            shape = RoundedCornerShape(16.dp)
                                                                        ) {
                                                                            Text("Add")
                                                                        }
                                                                    } else if (needsUpdate) {
                                                                        Button(
                                                                            onClick = { onAddOrUpdate(item) },
                                                                            shape = RoundedCornerShape(16.dp)
                                                                        ) {
                                                                            Text("Update")
                                                                        }
                                                                    } else {
                                                                        OutlinedButton(
                                                                            onClick = {},
                                                                            enabled = false,
                                                                            shape = RoundedCornerShape(16.dp)
                                                                        ) {
                                                                            Text("✓ Added")
                                                                        }
                                                                    }
                                                                }
                                                                if (isInstructionExpanded) {
                                                                    Spacer(modifier = Modifier.height(8.dp))
                                                                    Surface(
                                                                        shape = RoundedCornerShape(10.dp),
                                                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                                        modifier = Modifier.fillMaxWidth()
                                                                    ) {
                                                                        Text(
                                                                            text = item.prompt,
                                                                            style = MaterialTheme.typography.bodySmall,
                                                                            color = MaterialTheme.colorScheme.onSurface,
                                                                            modifier = Modifier.padding(10.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    item { Spacer(modifier = Modifier.height(16.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
