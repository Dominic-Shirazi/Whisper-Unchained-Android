// This file is called by MainActivity navigation.
// Inputs: SettingsRepository and ApiClient for trigger phrases and custom triggers synchronization.
// Outputs: TriggersScreen Composable managing built-in trigger phrases and custom triggers.
// Conforms to contracts/01-transcription-api.md §11, §13 and F-53, F-60, F-61.
// Reason for edit: Dedicated Triggers screen with editable built-in phrases ("Keep at least one" guard) and custom triggers with exact F-61 wording.

package com.example.whisperremote.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperremote.ApiClient
import com.example.whisperremote.CustomTriggersResult
import com.example.whisperremote.TriggerPhrasesResult
import com.example.whisperremote.data.CustomTrigger
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.QueuedMutation
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TriggerPhrase
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.theme.WhisperTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersScreen(
    repository: SettingsRepository,
    apiClient: ApiClient,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    val isSelfHosted = repository.providerPreset == ProviderPreset.SELF_HOSTED

    // Built-in trigger phrases state
    var triggerPhrases by remember { mutableStateOf(repository.parseCachedTriggerPhrases()) }
    var triggerPhrasesSupported by remember { mutableStateOf(true) }
    var isLoadingPhrases by remember { mutableStateOf(false) }

    // Dialog state for built-in phrases
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var editingPhraseTarget by remember { mutableStateOf<TriggerPhrase?>(null) }

    // Custom triggers state
    var customTriggers by remember { mutableStateOf<List<CustomTrigger>>(emptyList()) }
    var customTriggersSupported by remember { mutableStateOf(true) }
    var isLoadingCustomTriggers by remember { mutableStateOf(false) }

    // Dialog state for custom triggers
    var showWriteOwnDialog by remember { mutableStateOf(false) }
    var showLibraryDialog by remember { mutableStateOf(false) }
    var libraryTargetId by remember { mutableStateOf<String?>(null) }
    var editingCustomTrigger by remember { mutableStateOf<CustomTrigger?>(null) }

    // Position tracking for scroll-to link
    var customTriggersYOffset by remember { mutableStateOf(0f) }

    // Refresh built-in phrases and custom triggers
    fun refreshBuiltinPhrases() {
        if (isSelfHosted && repository.baseUrl.isNotBlank()) {
            isLoadingPhrases = true
            apiClient.fetchTriggerPhrases { res ->
                isLoadingPhrases = false
                when (res) {
                    is TriggerPhrasesResult.Success -> {
                        triggerPhrases = res.phrases
                        triggerPhrasesSupported = true
                    }
                    is TriggerPhrasesResult.Unsupported -> {
                        triggerPhrasesSupported = false
                    }
                    is TriggerPhrasesResult.Error -> {
                        if (res.statusCode == 404) triggerPhrasesSupported = false
                    }
                    else -> {}
                }
            }
        }
    }

    fun refreshCustomTriggers() {
        if (isSelfHosted && repository.baseUrl.isNotBlank()) {
            isLoadingCustomTriggers = true
            apiClient.fetchCustomTriggers { res ->
                isLoadingCustomTriggers = false
                when (res) {
                    is CustomTriggersResult.Success -> {
                        customTriggers = res.triggers
                        customTriggersSupported = true
                    }
                    is CustomTriggersResult.Unsupported -> {
                        customTriggersSupported = false
                    }
                    is CustomTriggersResult.Error -> {
                        if (res.statusCode == 404) customTriggersSupported = false
                    }
                    else -> {}
                }
            }
        }
    }

    LaunchedEffect(repository.providerPreset, repository.baseUrl) {
        refreshBuiltinPhrases()
        refreshCustomTriggers()
    }

    // Save whole trigger phrases list
    fun savePhrasesList(newList: List<TriggerPhrase>, onComplete: ((String?) -> Unit)? = null) {
        val prev = triggerPhrases
        triggerPhrases = newList

        val mutation = QueuedMutation(type = "phrases", triggerPhrases = newList)
        repository.queueWordMutation(mutation)

        apiClient.saveTriggerPhrases(newList) { res ->
            when (res) {
                is TriggerPhrasesResult.Success -> {
                    repository.removePendingMutation(mutation.id)
                    triggerPhrases = res.phrases
                    onComplete?.invoke(null)
                }
                is TriggerPhrasesResult.Error -> {
                    if (res.statusCode == 400) {
                        repository.removePendingMutation(mutation.id)
                        triggerPhrases = prev
                        onComplete?.invoke(res.message)
                        coroutineScope.launch { snackbarHostState.showSnackbar(res.message) }
                    } else {
                        onComplete?.invoke(null)
                    }
                }
                is TriggerPhrasesResult.Unsupported -> {
                    triggerPhrasesSupported = false
                    repository.removePendingMutation(mutation.id)
                    triggerPhrases = prev
                    onComplete?.invoke("Update the PC app to change these")
                }
                else -> {
                    // Kept queued for offline replay
                    onComplete?.invoke(null)
                }
            }
        }
    }

    fun onAddPhrase(phrase: String, action: String) {
        val trimmed = phrase.trim()
        val exists = triggerPhrases.any { it.phrase.equals(trimmed, ignoreCase = true) }
        if (exists) {
            Toast.makeText(context, "Phrase already exists", Toast.LENGTH_SHORT).show()
            return
        }
        val newList = triggerPhrases + TriggerPhrase(trimmed, action)
        savePhrasesList(newList) { err ->
            if (err == null) showAddPhraseDialog = false
        }
    }

    fun onEditPhrase(oldPhrase: TriggerPhrase, newText: String, newAction: String) {
        val trimmed = newText.trim()
        val other = triggerPhrases.filter { it.phrase != oldPhrase.phrase }
        if (other.any { it.phrase.equals(trimmed, ignoreCase = true) }) {
            Toast.makeText(context, "Phrase already exists", Toast.LENGTH_SHORT).show()
            return
        }
        // Guard: do not allow editing the last edit phrase to an "end" action
        val willHaveEdit = (oldPhrase.action == "edit" && newAction == "edit") || other.any { it.isEdit() }
        if (!willHaveEdit) {
            Toast.makeText(context, "Keep at least one phrase that starts an AI edit", Toast.LENGTH_SHORT).show()
            return
        }

        val updated = TriggerPhrase(trimmed, newAction)
        val newList = triggerPhrases.map { if (it.phrase == oldPhrase.phrase) updated else it }
        savePhrasesList(newList) { err ->
            if (err == null) editingPhraseTarget = null
        }
    }

    fun onRemovePhrase(phrase: TriggerPhrase) {
        // Guard: At least one "edit" phrase must remain per F-60 §1 item 2
        if (phrase.isEdit() && triggerPhrases.count { it.isEdit() } <= 1) {
            Toast.makeText(context, "Keep at least one phrase that starts an AI edit", Toast.LENGTH_SHORT).show()
            return
        }

        val index = triggerPhrases.indexOf(phrase)
        val newList = triggerPhrases.filter { it.phrase != phrase.phrase }
        savePhrasesList(newList)

        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Removed \"${phrase.phrase}\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val restored = triggerPhrases.toMutableList()
                if (index in 0..restored.size) restored.add(index, phrase) else restored.add(phrase)
                savePhrasesList(restored)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Triggers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ==========================================
            // 1. TRIGGER PHRASES (F-61 §1)
            // ==========================================
            Text(
                text = "Trigger phrases",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Say one of these while dictating, then tell the AI what you want.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Custom triggers ↓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable {
                        coroutineScope.launch {
                            scrollState.animateScrollTo(1400)
                        }
                    }
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Example box as on Windows
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val examplePromptString = buildAnnotatedString {
                        append("You say: \"hey can you send me the report by friday ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                            append("prompt AI")
                        }
                        append(" make it polite\"")
                    }
                    Text(
                        text = examplePromptString,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "It pastes: \"Hi! Could you please send me the report by Friday? Thank you.\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!isSelfHosted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "Built-in trigger phrases are edited on your PC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else if (!triggerPhrasesSupported) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "Update the PC app to edit trigger phrases.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // List of phrases
            SettingsGroup(modifier = Modifier.padding(bottom = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val editPhrasesCount = triggerPhrases.count { it.isEdit() }

                    triggerPhrases.forEach { phrase ->
                        val isLastEdit = phrase.isEdit() && editPhrasesCount <= 1
                        val actionDesc = if (phrase.isEdit()) "Starts an AI edit" else "Ends the instruction"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = phrase.phrase,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = actionDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (phrase.isEdit()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isSelfHosted && triggerPhrasesSupported) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { editingPhraseTarget = phrase }) {
                                        Text("Edit")
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    if (isLastEdit) {
                                        Text(
                                            text = "Keep at least one",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    } else {
                                        TextButton(onClick = { onRemovePhrase(phrase) }) {
                                            Text("Remove", color = Color(0xFFD9694A))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isSelfHosted && triggerPhrasesSupported) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showAddPhraseDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add a phrase")
                        }
                    }
                }
            }

            // Tip under the list
            Text(
                text = "Keeps mishearing it? Add what it hears as another phrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 24.dp)
            )

            SettingsDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ==========================================
            // 2. CUSTOM TRIGGERS (F-61 §2)
            // ==========================================
            Text(
                text = "Custom triggers",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "A custom trigger is your own phrase for an instruction you've saved. Say the phrase and the AI follows that instruction, so you never explain it twice.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Example box (new F-61 §2)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val intakeExampleString = buildAnnotatedString {
                        append("Example: a client emails you their details in one long paragraph. Paste it into your notes, highlight it, tap the mic button and say ")
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                            append("prompt intake")
                        }
                        append(". It comes back as your intake sheet: name, phone, email, address and date of birth for each person, in the same order every time, with \"unknown\" beside anything they didn't say.")
                    }
                    Text(
                        text = intakeExampleString,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Add 'Client intake sheet' from the library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                libraryTargetId = "intake"
                                showLibraryDialog = true
                            }
                            .padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Three short points
            Column(modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
                Text(
                    text = "• Say the phrase anywhere while dictating: everything else you said is what it works on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Text(
                    text = "• Or highlight text first, then say just the phrase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Text(
                    text = "• It uses the AI set up in AI Editing. A stronger AI can do harder jobs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (!isSelfHosted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "Custom triggers only run through your PC for now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else if (!customTriggersSupported) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "Update the PC app to use custom triggers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                // Action buttons: Write your own, Browse the library
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
                        Text("Write your own")
                    }
                    OutlinedButton(
                        onClick = {
                            libraryTargetId = null
                            showLibraryDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text("Browse the library")
                    }
                }

                if (isLoadingCustomTriggers && customTriggers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (customTriggers.isEmpty()) {
                    SettingsGroup(modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "No custom triggers yet. Add one from the library or write your own.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    customTriggers.forEach { trigger ->
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
                                    TextButton(onClick = { editingCustomTrigger = trigger }) {
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

            Spacer(modifier = Modifier.height(10.dp))
            // Small last line per F-61 §2
            Text(
                text = "Looking for mean mode? It's in the library, under Fun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 24.dp)
            )
        }
    }

    // ==========================================
    // DIALOGS
    // ==========================================

    // Add Built-in Phrase Dialog
    if (showAddPhraseDialog) {
        var phraseText by remember { mutableStateOf("") }
        var selectedAction by remember { mutableStateOf("edit") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddPhraseDialog = false },
            title = { Text("Add trigger phrase") },
            text = {
                Column {
                    OutlinedTextField(
                        value = phraseText,
                        onValueChange = {
                            phraseText = it
                            errorMessage = null
                        },
                        label = { Text("Phrase (2-60 characters)") },
                        placeholder = { Text("e.g. prompt AI") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "What does this phrase do?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAction = "edit" }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedAction == "edit",
                            onClick = { selectedAction = "edit" }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Starts an AI edit")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAction = "end" }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedAction == "end",
                            onClick = { selectedAction = "end" }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ends the instruction")
                    }

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
                    onClick = {
                        val trimmed = phraseText.trim()
                        if (trimmed.length < 2 || trimmed.length > 60 || trimmed.contains(",")) {
                            errorMessage = "Phrase must be 2-60 characters and cannot contain commas"
                            return@Button
                        }
                        onAddPhrase(trimmed, selectedAction)
                    },
                    enabled = phraseText.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPhraseDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Built-in Phrase Dialog
    if (editingPhraseTarget != null) {
        val target = editingPhraseTarget!!
        var phraseText by remember { mutableStateOf(target.phrase) }
        var selectedAction by remember { mutableStateOf(target.action) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editingPhraseTarget = null },
            title = { Text("Edit trigger phrase") },
            text = {
                Column {
                    OutlinedTextField(
                        value = phraseText,
                        onValueChange = {
                            phraseText = it
                            errorMessage = null
                        },
                        label = { Text("Phrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "What does this phrase do?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAction = "edit" }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedAction == "edit",
                            onClick = { selectedAction = "edit" }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Starts an AI edit")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAction = "end" }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedAction == "end",
                            onClick = { selectedAction = "end" }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ends the instruction")
                    }

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
                    onClick = {
                        val trimmed = phraseText.trim()
                        if (trimmed.length < 2 || trimmed.length > 60 || trimmed.contains(",")) {
                            errorMessage = "Phrase must be 2-60 characters and cannot contain commas"
                            return@Button
                        }
                        onEditPhrase(target, trimmed, selectedAction)
                    },
                    enabled = phraseText.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPhraseTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Write your own custom trigger dialog
    if (showWriteOwnDialog) {
        TriggerEditorDialog(
            title = "Write your own trigger",
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
                            callback("Failed to save trigger")
                        }
                    }
                }
            }
        )
    }

    // Edit custom trigger dialog
    if (editingCustomTrigger != null) {
        TriggerEditorDialog(
            title = "Edit custom trigger",
            initialTrigger = editingCustomTrigger,
            onDismiss = { editingCustomTrigger = null },
            onSave = { trigger, callback ->
                apiClient.modifyCustomTriggers(set = listOf(trigger)) { res ->
                    when (res) {
                        is CustomTriggersResult.Success -> {
                            customTriggers = res.triggers
                            callback(null)
                            editingCustomTrigger = null
                        }
                        is CustomTriggersResult.Error -> {
                            callback(res.message)
                        }
                        is CustomTriggersResult.NetworkError -> {
                            callback(res.error.message ?: "Network error")
                        }
                        else -> {
                            callback("Failed to update trigger")
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
            targetId = libraryTargetId,
            onDismiss = {
                showLibraryDialog = false
                libraryTargetId = null
            },
            onAddOrUpdate = { item ->
                val newTrigger = CustomTrigger(
                    id = item.id,
                    name = item.name,
                    phrases = item.phrases,
                    prompt = item.prompt,
                    enabled = true,
                    libraryId = item.id,
                    libraryVersion = item.libraryVersion ?: 1,
                    author = item.author,
                    temperature = item.temperature,
                    description = item.description,
                    category = item.category,
                    model = item.model
                )
                apiClient.modifyCustomTriggers(set = listOf(newTrigger)) { res ->
                    if (res is CustomTriggersResult.Success) {
                        customTriggers = res.triggers
                    }
                }
            }
        )
    }
}
