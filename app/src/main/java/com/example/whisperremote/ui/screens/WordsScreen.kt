// This file is called by MainActivity navigation.
// Inputs: SettingsRepository and ApiClient for 2-way vocabulary and corrections synchronization.
// Outputs: WordsScreen Composable managing Vocabulary and Corrections & shortcuts without tabs.
// Conforms to contracts/01-transcription-api.md §8, §13 and F-50, F-60.
// Reason for edit: Dedicated Words screen with Vocabulary and Corrections & shortcuts (no tabs) per F-60.

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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
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
fun WordsScreen(
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
                is VocabularyResult.ParseError -> {}
                is VocabularyResult.NetworkError -> {}
            }
        }
    }

    fun onEditHelperWord(oldWord: String, newWord: String) {
        val trimmed = newWord.trim()
        if (trimmed.equals(oldWord, ignoreCase = false)) {
            editingWordTarget = null
            return
        }
        val otherWords = helperWords.filter { it != oldWord }
        val error = WordRulesValidator.validateHelperWord(trimmed, existingWords = otherWords)
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            return
        }
        editingWordTarget = null
        helperWords = otherWords + trimmed

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
                        helperWords = helperWords - trimmed + oldWord
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is VocabularyResult.ParseError -> {}
                is VocabularyResult.NetworkError -> {}
            }
        }
    }

    fun onRemoveHelperWord(word: String) {
        val index = helperWords.indexOf(word)
        helperWords = helperWords - word

        val mutation = QueuedMutation(type = "vocab", removeWords = listOf(word))
        repository.queueWordMutation(mutation)

        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Removed \"$word\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                repository.removePendingMutation(mutation.id)
                val restored = helperWords.toMutableList()
                if (index in 0..restored.size) restored.add(index, word) else restored.add(word)
                helperWords = restored

                val undoMutation = QueuedMutation(type = "vocab", addWords = listOf(word))
                repository.queueWordMutation(undoMutation)
                apiClient.modifyVocabulary(add = listOf(word)) { undoRes ->
                    if (undoRes is VocabularyResult.Success) {
                        repository.removePendingMutation(undoMutation.id)
                        helperWords = undoRes.data.vocabulary
                        fromNameCorrectionsWords = undoRes.data.fromNameCorrections
                        serverNameCorrections = undoRes.data.nameCorrections
                    }
                }
            }
        }

        apiClient.modifyVocabulary(remove = listOf(word)) { result ->
            when (result) {
                is VocabularyResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    helperWords = result.data.vocabulary
                    fromNameCorrectionsWords = result.data.fromNameCorrections
                    serverNameCorrections = result.data.nameCorrections
                }
                is VocabularyResult.Error -> {}
                is VocabularyResult.ParseError -> {}
                is VocabularyResult.NetworkError -> {}
            }
        }
    }

    fun onAddServerCorrection(whatItHears: String, whatYouWant: String) {
        val hearsTrim = whatItHears.trim()
        val wantTrim = whatYouWant.trim()
        val error = WordRulesValidator.validateNameCorrection(hearsTrim, wantTrim, existingHeardList = serverNameCorrections.keys)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        showAddCorrectionDialog = false

        val newMap = serverNameCorrections.toMutableMap()
        newMap[hearsTrim] = wantTrim
        serverNameCorrections = newMap

        val mutation = QueuedMutation(type = "name", setNameCorrections = mapOf(hearsTrim to wantTrim))
        repository.queueWordMutation(mutation)

        apiClient.modifyNameCorrections(set = mapOf(hearsTrim to wantTrim)) { result ->
            when (result) {
                is NameCorrectionsResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    serverNameCorrections = result.nameCorrections
                }
                is NameCorrectionsResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        val reverted = serverNameCorrections.toMutableMap()
                        reverted.remove(hearsTrim)
                        serverNameCorrections = reverted
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is NameCorrectionsResult.Unsupported -> {
                    nameCorrectionsSupported = false
                }
                is NameCorrectionsResult.ParseError -> {}
                is NameCorrectionsResult.NetworkError -> {}
            }
        }
    }

    fun onEditServerCorrection(oldHears: String, newHears: String, newWant: String) {
        val hearsTrim = newHears.trim()
        val wantTrim = newWant.trim()
        val other = serverNameCorrections.filterKeys { it != oldHears }
        val error = WordRulesValidator.validateNameCorrection(hearsTrim, wantTrim, existingHeardList = serverNameCorrections.keys, currentHeard = oldHears)
        if (error != null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        editingCorrectionTarget = null

        val newMap = other.toMutableMap()
        newMap[hearsTrim] = wantTrim
        serverNameCorrections = newMap

        val setMap = mapOf(hearsTrim to wantTrim)
        val removeList = if (hearsTrim != oldHears) listOf(oldHears) else emptyList()
        val mutation = QueuedMutation(type = "name", setNameCorrections = setMap, removeNameCorrections = removeList)
        repository.queueWordMutation(mutation)

        apiClient.modifyNameCorrections(set = setMap, remove = removeList) { result ->
            when (result) {
                is NameCorrectionsResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    serverNameCorrections = result.nameCorrections
                }
                is NameCorrectionsResult.Error -> {
                    if (result.code == 400) {
                        repository.removePendingMutation(mutation.id)
                        coroutineScope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }
                is NameCorrectionsResult.Unsupported -> {
                    nameCorrectionsSupported = false
                }
                is NameCorrectionsResult.ParseError -> {}
                is NameCorrectionsResult.NetworkError -> {}
            }
        }
    }

    fun onRemoveServerCorrection(hears: String) {
        val oldTarget = serverNameCorrections[hears] ?: return
        val newMap = serverNameCorrections.toMutableMap()
        newMap.remove(hears)
        serverNameCorrections = newMap

        val mutation = QueuedMutation(type = "name", removeNameCorrections = listOf(hears))
        repository.queueWordMutation(mutation)

        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Removed \"$hears → $oldTarget\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                repository.removePendingMutation(mutation.id)
                val restored = serverNameCorrections.toMutableMap()
                restored[hears] = oldTarget
                serverNameCorrections = restored

                val undoMutation = QueuedMutation(type = "name", setNameCorrections = mapOf(hears to oldTarget))
                repository.queueWordMutation(undoMutation)
                apiClient.modifyNameCorrections(set = mapOf(hears to oldTarget)) { undoRes ->
                    if (undoRes is NameCorrectionsResult.Success) {
                        repository.removePendingMutation(undoMutation.id)
                        serverNameCorrections = undoRes.nameCorrections
                    }
                }
            }
        }

        apiClient.modifyNameCorrections(remove = listOf(hears)) { result ->
            when (result) {
                is NameCorrectionsResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    serverNameCorrections = result.nameCorrections
                }
                is NameCorrectionsResult.Error -> {}
                is NameCorrectionsResult.Unsupported -> {
                    nameCorrectionsSupported = false
                }
                is NameCorrectionsResult.ParseError -> {}
                is NameCorrectionsResult.NetworkError -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Words") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                        IconButton(onClick = { syncVocabulary() }, enabled = !isSyncing) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync with PC")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val isSelfHosted = repository.providerPreset == ProviderPreset.SELF_HOSTED

            if (isSelfHosted) {
                // Section 1: Vocabulary (Helper words)
                SettingsGroup(
                    header = "Vocabulary",
                    footer = "Words and names you want Whisper to recognise. They work everywhere: on this PC and on phones paired to it."
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Helper words (${helperWords.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val budgetColor = if (charBudgetChars > charBudgetMax) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            Text(
                                text = "$charBudgetChars / $charBudgetMax chars",
                                style = MaterialTheme.typography.bodySmall,
                                color = budgetColor
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newWordInput,
                                onValueChange = {
                                    newWordInput = it
                                    if (newWordError != null) newWordError = null
                                },
                                placeholder = { Text("Add word or name") },
                                isError = newWordError != null,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onAddHelperWord(newWordInput) },
                                enabled = newWordInput.isNotBlank()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        if (newWordError != null) {
                            Text(
                                text = newWordError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (helperWords.isEmpty()) {
                            Text(
                                text = "No helper words added yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            helperWords.forEach { word ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = word,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { editingWordTarget = word }
                                    )
                                    IconButton(
                                        onClick = { onRemoveHelperWord(word) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove $word",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        if (effectiveFromNameCorrections.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            SettingsDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Also sent to Whisper from your corrections below: ${effectiveFromNameCorrections.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(WhisperTokens.GroupGap))

                // Section 2: Corrections & shortcuts
                SettingsGroup(
                    header = "Corrections & shortcuts",
                    footer = "Replace misheard words automatically after transcription, or set up text expanding shortcuts (up to 200 characters)."
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Replacements (${serverNameCorrections.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = { showAddCorrectionDialog = true }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (serverNameCorrections.isEmpty()) {
                            Text(
                                text = "No corrections yet. Add one to fix recurring mistakes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            serverNameCorrections.forEach { (hears, target) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = hears,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { editingCorrectionTarget = Pair(hears, target) }
                                    )
                                    Text(
                                        text = "→",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    Text(
                                        text = target,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { editingCorrectionTarget = Pair(hears, target) }
                                    )
                                    IconButton(
                                        onClick = { onRemoveServerCorrection(hears) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove $hears",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-PC (Cloud / On-device) local vocabulary & corrections
                SettingsGroup(
                    header = "Local Vocabulary & Hints",
                    footer = "Custom vocabulary hints passed to Whisper during transcription."
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = localPromptHints,
                            onValueChange = {
                                localPromptHints = it
                                repository.promptHints = it
                            },
                            label = { Text("Prompt hints") },
                            placeholder = { Text("Comma-separated words or names") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsSwitchRow(
                            title = "Auto-trim hints",
                            subtitle = "Automatically trim whitespace from prompt hints",
                            checked = autoTrimHints,
                            onCheckedChange = {
                                autoTrimHints = it
                                repository.autoTrimHints = it
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(WhisperTokens.GroupGap))

                SettingsGroup(
                    header = "Local Corrections",
                    footer = "Replacements applied locally after transcription."
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        localCorrectionsMap.forEach { (k, v) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("$k → $v", style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = {
                                    val newMap = localCorrectionsMap.toMutableMap()
                                    newMap.remove(k)
                                    localCorrectionsMap = newMap
                                    repository.localNameCorrections = TextRules.formatCorrectionsText(newMap)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit Word Dialog
    if (editingWordTarget != null) {
        var editedWord by remember { mutableStateOf(editingWordTarget!!) }
        AlertDialog(
            onDismissRequest = { editingWordTarget = null },
            title = { Text("Edit word") },
            text = {
                OutlinedTextField(
                    value = editedWord,
                    onValueChange = { editedWord = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { onEditHelperWord(editingWordTarget!!, editedWord) }) {
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

    // Add Correction Dialog
    if (showAddCorrectionDialog) {
        var hearsText by remember { mutableStateOf("") }
        var wantText by remember { mutableStateOf("") }
        val placeholders = listOf(
            Pair("brb", "be right back"),
            Pair("omw", "on my way"),
            Pair("Dominic", "Dominick"),
            Pair("my email", "me@example.com")
        )
        var placeholderIndex by remember { mutableIntStateOf((placeholders.indices).random()) }

        AlertDialog(
            onDismissRequest = { showAddCorrectionDialog = false },
            title = { Text("Add correction or shortcut") },
            text = {
                Column {
                    OutlinedTextField(
                        value = hearsText,
                        onValueChange = { hearsText = it },
                        label = { Text("When it hears / you say") },
                        placeholder = { Text(placeholders[placeholderIndex].first) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = wantText,
                        onValueChange = { if (it.length <= 200) wantText = it },
                        label = { Text("Replace with (${wantText.length}/200)") },
                        placeholder = { Text(placeholders[placeholderIndex].second) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onAddServerCorrection(hearsText, wantText) },
                    enabled = hearsText.isNotBlank() && wantText.isNotBlank()
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

    // Edit Correction Dialog
    if (editingCorrectionTarget != null) {
        val (initialHears, initialWant) = editingCorrectionTarget!!
        var hearsText by remember { mutableStateOf(initialHears) }
        var wantText by remember { mutableStateOf(initialWant) }

        AlertDialog(
            onDismissRequest = { editingCorrectionTarget = null },
            title = { Text("Edit correction") },
            text = {
                Column {
                    OutlinedTextField(
                        value = hearsText,
                        onValueChange = { hearsText = it },
                        label = { Text("When it hears") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = wantText,
                        onValueChange = { if (it.length <= 200) wantText = it },
                        label = { Text("Replace with (${wantText.length}/200)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onEditServerCorrection(initialHears, hearsText, wantText) },
                    enabled = hearsText.isNotBlank() && wantText.isNotBlank()
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
