// This file is called by MainActivity navigation.
// Inputs: SettingsRepository and ApiClient for built-in AI-edit phrases and skills (custom triggers) synchronization.
// Outputs: TriggersScreen Composable presenting the "Skills" screen, the "What you say to ask the AI" catchphrase editor screen, and the "New skill" / "Edit skill" screen.
// Reason for edit: Update user-visible "phrase(s)" wording to "catchphrase(s)" across Skills, catchphrase editor, and dialogs per catchphrase wording catch-up.
// Conforms to contracts/01-transcription-api.md §11, §13 and F-53, F-60, F-61, F-62, F-63.
// Reason for edit: Constrain content width to 640.dp centered on foldable/tablet screens per F-71.
// Reason for edit: Shorten Phrase Editor example first part to "send me the report by friday" without horizontal scroll per F-63.
// Reason for edit: Remodel screen to "Skills" with top "Ask the AI to change your text" card, compact "Your skills" list visible without scrolling, dedicated Phrase Editor screen with 3-colour labelled example, and dedicated New Skill screen with explanation & client-intake example per F-62.
// Reason for edit: Dedicated Triggers screen with editable built-in phrases ("Keep at least one" guard) and custom triggers with exact F-61 wording.

package com.example.whisperremote.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
import com.example.whisperremote.util.PromptPanelLogic
import kotlinx.coroutines.launch

private enum class SkillsSubView {
    MAIN,
    PHRASE_EDITOR,
    SKILL_EDITOR
}

private val ORIGINAL_DEFAULT_PHRASES = listOf(
    TriggerPhrase("prompt AI", "edit"),
    TriggerPhrase("prompte I", "edit"),
    TriggerPhrase("end prompt", "end")
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TriggersScreen(
    repository: SettingsRepository,
    apiClient: ApiClient,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSkills: List<CustomTrigger> = emptyList()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isSelfHosted = repository.providerPreset == ProviderPreset.SELF_HOSTED
    // Reason for edit: F-79 — when no PC is paired, Skills screen, New/Edit skill, and Library operate on the phone's local skills list per contract 05 §7 items 1-2
    val isLocalSkillsMode = repository.usesLocalSkills()

    var currentSubView by remember { mutableStateOf(SkillsSubView.MAIN) }

    // Built-in phrases state
    var triggerPhrases by remember { mutableStateOf(repository.parseCachedTriggerPhrases()) }
    var triggerPhrasesSupported by remember { mutableStateOf(true) }
    var isLoadingPhrases by remember { mutableStateOf(false) }

    // Dialog state for built-in phrases
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var editingPhraseTarget by remember { mutableStateOf<TriggerPhrase?>(null) }

    // Skills (custom triggers) state
    var customTriggers by remember { mutableStateOf(initialSkills) }
    var customTriggersSupported by remember { mutableStateOf(true) }
    var isLoadingCustomTriggers by remember { mutableStateOf(false) }

    // Skill editor & library state
    var editingCustomTrigger by remember { mutableStateOf<CustomTrigger?>(null) }
    var showLibraryDialog by remember { mutableStateOf(false) }
    var libraryTargetId by remember { mutableStateOf<String?>(null) }

    // Intercept system back button when in Phrase Editor or Skill Editor sub-screen
    BackHandler(enabled = currentSubView != SkillsSubView.MAIN) {
        currentSubView = SkillsSubView.MAIN
        editingCustomTrigger = null
    }

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
        if (isLocalSkillsMode) {
            customTriggers = repository.getLocalSkills()
            customTriggersSupported = true
            isLoadingCustomTriggers = false
            return
        }
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
                        val msg = PromptPanelLogic.skillWording(res.message)
                        onComplete?.invoke(msg)
                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
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
                    onComplete?.invoke(null)
                }
            }
        }
    }

    fun onAddPhrase(phrase: String, action: String) {
        val trimmed = phrase.trim()
        val exists = triggerPhrases.any { it.phrase.equals(trimmed, ignoreCase = true) }
        if (exists) {
            Toast.makeText(context, "Catchphrase already exists", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Catchphrase already exists", Toast.LENGTH_SHORT).show()
            return
        }
        val willHaveEdit = (oldPhrase.action == "edit" && newAction == "edit") || other.any { it.isEdit() }
        if (!willHaveEdit) {
            Toast.makeText(context, "Keep at least one catchphrase that starts an AI edit", Toast.LENGTH_SHORT).show()
            return
        }

        val updated = TriggerPhrase(trimmed, newAction)
        val newList = triggerPhrases.map { if (it.phrase == oldPhrase.phrase) updated else it }
        savePhrasesList(newList) { err ->
            if (err == null) editingPhraseTarget = null
        }
    }

    fun onRemovePhrase(phrase: TriggerPhrase) {
        if (phrase.isEdit() && triggerPhrases.count { it.isEdit() } <= 1) {
            Toast.makeText(context, "Keep at least one catchphrase that starts an AI edit", Toast.LENGTH_SHORT).show()
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

    fun onRestoreOriginalPhrases() {
        val prev = triggerPhrases
        savePhrasesList(ORIGINAL_DEFAULT_PHRASES)
        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Restored original catchphrases",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                savePhrasesList(prev)
            }
        }
    }

    val topBarTitle = when (currentSubView) {
        SkillsSubView.MAIN -> "Skills"
        SkillsSubView.PHRASE_EDITOR -> "What you say to ask the AI"
        SkillsSubView.SKILL_EDITOR -> if (editingCustomTrigger == null) "New skill" else "Edit skill"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentSubView == SkillsSubView.MAIN) {
                                onNavigateBack()
                            } else {
                                currentSubView = SkillsSubView.MAIN
                                editingCustomTrigger = null
                            }
                        }
                    ) {
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
                            // Reason for edit: Handle system Back inside Phrase Editor and Skill Editor to return to MAIN Skills view per F-71
                            BackHandler(enabled = currentSubView != SkillsSubView.MAIN) {
                                currentSubView = SkillsSubView.MAIN
                                editingCustomTrigger = null
                            }

                            when (currentSubView) {
            // =========================================================================
            // 1. MAIN SKILLS SCREEN (F-62 Screen layout §1 & §2)
            // =========================================================================
            SkillsSubView.MAIN -> {
                val mainScroll = rememberScrollState()
                val firstEditPhrase = triggerPhrases.firstOrNull { it.isEdit() }?.phrase.orEmpty()
                val (askBodyLine, askExampleLine) = PromptPanelLogic.askAiLines(firstEditPhrase)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 640.dp)
                        .verticalScroll(mainScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Top card: "Ask the AI to change your text"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = PromptPanelLogic.ASK_CARD_TITLE,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = askBodyLine,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (askExampleLine.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = askExampleLine,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { currentSubView = SkillsSubView.PHRASE_EDITOR },
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(PromptPanelLogic.ASK_CARD_BUTTON)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Directly under the card, visible without scrolling:
                    // Heading "Your skills" with "+ New skill" and "Browse the library"
                    Text(
                        text = "Your skills",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (!isSelfHosted && !isLocalSkillsMode) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text(
                                text = "Skills only run through your PC for now.",
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
                                text = "Update the PC app to use skills",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    editingCustomTrigger = null
                                    currentSubView = SkillsSubView.SKILL_EDITOR
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("+ New skill", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    libraryTargetId = null
                                    showLibraryDialog = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Browse the library", maxLines = 1)
                            }
                        }

                        if (isLoadingCustomTriggers && customTriggers.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        } else if (customTriggers.isEmpty()) {
                            SettingsGroup(modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "None yet. Start with one from the library, or make your own.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            // Compact skill rows: switch, name, say "<first phrase>" (+N more), Edit, Remove
                            SettingsGroup(modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                    customTriggers.forEachIndexed { index, skill ->
                                        if (index > 0) {
                                            SettingsDivider(insetStart = false)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Switch(
                                                checked = skill.enabled,
                                                onCheckedChange = { isChecked ->
                                                    val updated = skill.copy(enabled = isChecked)
                                                    val prev = customTriggers
                                                    val nextList = customTriggers.map { if (it.id == skill.id) updated else it }
                                                    customTriggers = nextList
                                                    if (isLocalSkillsMode) {
                                                        repository.saveLocalSkills(nextList)
                                                    } else {
                                                        apiClient.modifyCustomTriggers(set = listOf(updated)) { res ->
                                                            if (res is CustomTriggersResult.Success) {
                                                                customTriggers = res.triggers
                                                            } else {
                                                                customTriggers = prev
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = skill.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                val saySummary = PromptPanelLogic.skillSummary(skill.phrases)
                                                if (saySummary.isNotEmpty()) {
                                                    Text(
                                                        text = saySummary,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    editingCustomTrigger = skill
                                                    currentSubView = SkillsSubView.SKILL_EDITOR
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Edit")
                                            }
                                            TextButton(
                                                onClick = {
                                                    val removed = skill
                                                    val prev = customTriggers
                                                    val nextList = customTriggers.filter { it.id != skill.id }
                                                    customTriggers = nextList
                                                    if (isLocalSkillsMode) {
                                                        repository.saveLocalSkills(nextList)
                                                    } else {
                                                        apiClient.modifyCustomTriggers(remove = listOf(skill.id)) { res ->
                                                            if (res is CustomTriggersResult.Success) {
                                                                customTriggers = res.triggers
                                                            }
                                                        }
                                                    }
                                                    coroutineScope.launch {
                                                        val snack = snackbarHostState.showSnackbar(
                                                            message = "Removed '${skill.name}'",
                                                            actionLabel = "Undo",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                        if (snack == SnackbarResult.ActionPerformed) {
                                                            if (isLocalSkillsMode) {
                                                                customTriggers = prev
                                                                repository.saveLocalSkills(prev)
                                                            } else {
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
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
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
            }

            // =========================================================================
            // 2. PHRASE EDITOR SCREEN ("What you say to ask the AI")
            // =========================================================================
            SkillsSubView.PHRASE_EDITOR -> {
                val phraseScroll = rememberScrollState()
                val messageBlue = Color(0xFF6CB4FF)
                val directionsAmber = Color(0xFFF0B44C)
                val normalColor = MaterialTheme.colorScheme.onSurface

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 640.dp)
                        .verticalScroll(phraseScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Say one of these while dictating, then tell the AI what you want.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Three-colour labelled example box (F-62 & F-63 Phrase editor screen)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Text(
                                        text = "You say: \"",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = normalColor,
                                        softWrap = false
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "send me the report by friday",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = messageBlue,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "message",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = messageBlue,
                                        softWrap = false
                                    )
                                }
                                Column {
                                    Text(
                                        text = " ",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = normalColor,
                                        softWrap = false
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "prompt AI",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = normalColor,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "catchphrase",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = normalColor,
                                        softWrap = false
                                    )
                                }
                                Column {
                                    Text(
                                        text = " ",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = normalColor,
                                        softWrap = false
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "make it polite",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = directionsAmber,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "directions",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = directionsAmber,
                                        softWrap = false
                                    )
                                }
                                Column {
                                    Text(
                                        text = "\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Normal,
                                        color = normalColor,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Result: \"Hi! Could you please send me the report by Friday? Thank you.\"",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Normal,
                                color = normalColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "An \"End of instruction\" catchphrase is optional: say it when your directions are done. Capitals and commas don't matter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (!isSelfHosted && !isLocalSkillsMode) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text(
                                text = "Catchphrases for asking the AI are edited on your PC.",
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
                                text = "Update the PC app to change these",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }

                    // Editable list of phrases
                    SettingsGroup(modifier = Modifier.padding(bottom = 8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val editPhrasesCount = triggerPhrases.count { it.isEdit() }

                            triggerPhrases.forEachIndexed { idx, phrase ->
                                if (idx > 0) {
                                    SettingsDivider(insetStart = false)
                                }
                                val isLastEdit = phrase.isEdit() && editPhrasesCount <= 1
                                val actionDesc = if (phrase.isEdit()) "AI edit" else "End of instruction"

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
                                    Text("Add a catchphrase")
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = { onRestoreOriginalPhrases() },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Restore original catchphrases")
                                }
                            }
                        }
                    }

                    Text(
                        text = "Keeps mishearing it? Add what it hears as another catchphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 24.dp)
                    )
                }
            }

            // =========================================================================
            // 3. NEW SKILL / EDIT SKILL SCREEN (F-62 Screen layout §3)
            // =========================================================================
            SkillsSubView.SKILL_EDITOR -> {
                val initialSkill = editingCustomTrigger
                val isNew = initialSkill == null
                val editorScroll = rememberScrollState()

                var name by remember(initialSkill) { mutableStateOf(initialSkill?.name ?: "") }
                var phrasesText by remember(initialSkill) { mutableStateOf(initialSkill?.phrases?.joinToString("\n") ?: "") }
                var prompt by remember(initialSkill) { mutableStateOf(initialSkill?.prompt ?: "") }
                var errorMessage by remember(initialSkill) { mutableStateOf<String?>(null) }
                var isSaving by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 640.dp)
                        .verticalScroll(editorScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (isNew) {
                        Text(
                            text = "A skill is your own catchphrase for an instruction you've saved. Say the catchphrase and the AI follows that instruction, so you never explain it twice.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "The catchphrase can be anything you like: \"prompt intake\", \"skill intake\", or your own words.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; errorMessage = null },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Bug ticket") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "What you'll say",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "One catchphrase per line. Pick words you wouldn't say by accident, such as \"prompt ticket\" or \"skill ticket\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = phrasesText,
                        onValueChange = { phrasesText = it; errorMessage = null },
                        placeholder = { Text("e.g. prompt ticket\nskill ticket") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "What the AI should do",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Write it the way you'd explain the job to a person. It is applied to what you dictated, or to the text you highlighted first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it; errorMessage = null },
                        placeholder = { Text("Describe how the AI should rewrite or handle the speech...") },
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

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                                    errorMessage = "Provide between 1 and 6 catchphrases"
                                    return@Button
                                }
                                for (p in lines) {
                                    if (p.length < 2 || p.length > 60 || p.contains(",")) {
                                        errorMessage = "Catchphrase '$p' must be 2-60 characters and cannot contain commas"
                                        return@Button
                                    }
                                }
                                val trimmedPrompt = prompt.trim()
                                if (trimmedPrompt.length < 10 || trimmedPrompt.length > 6000) {
                                    errorMessage = "Instruction must be between 10 and 6000 characters"
                                    return@Button
                                }

                                val id = initialSkill?.id ?: trimmedName.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-').ifBlank { java.util.UUID.randomUUID().toString().take(8) }
                                val promptChanged = initialSkill != null && trimmedPrompt != initialSkill.prompt.trim()
                                val skillToSave = CustomTrigger(
                                    id = id,
                                    name = trimmedName,
                                    phrases = lines,
                                    prompt = trimmedPrompt,
                                    enabled = initialSkill?.enabled ?: true,
                                    libraryId = if (promptChanged) null else initialSkill?.libraryId,
                                    libraryVersion = if (promptChanged) null else initialSkill?.libraryVersion,
                                    author = initialSkill?.author,
                                    temperature = initialSkill?.temperature,
                                    description = initialSkill?.description,
                                    category = initialSkill?.category,
                                    model = initialSkill?.model
                                )

                                if (isLocalSkillsMode) {
                                    val validationError = com.example.whisperremote.util.LocalSkillEngine.validateSkill(
                                        name = trimmedName,
                                        phrases = lines,
                                        prompt = trimmedPrompt,
                                        existingSkills = customTriggers,
                                        editingId = initialSkill?.id
                                    )
                                    if (validationError != null) {
                                        errorMessage = validationError
                                        return@Button
                                    }
                                    val nextList = if (initialSkill != null) {
                                        customTriggers.map { if (it.id == initialSkill.id) skillToSave else it }
                                    } else {
                                        customTriggers + skillToSave
                                    }
                                    customTriggers = nextList
                                    repository.saveLocalSkills(nextList)
                                    editingCustomTrigger = null
                                    currentSubView = SkillsSubView.MAIN
                                    return@Button
                                }

                                isSaving = true
                                apiClient.modifyCustomTriggers(set = listOf(skillToSave)) { res ->
                                    isSaving = false
                                    when (res) {
                                        is CustomTriggersResult.Success -> {
                                            customTriggers = res.triggers
                                            editingCustomTrigger = null
                                            currentSubView = SkillsSubView.MAIN
                                        }
                                        is CustomTriggersResult.Error -> {
                                            errorMessage = PromptPanelLogic.skillWording(res.message)
                                        }
                                        is CustomTriggersResult.NetworkError -> {
                                            errorMessage = res.error.message ?: "Network error"
                                        }
                                        else -> {
                                            errorMessage = "Failed to save skill"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            } else {
                                Text(if (isNew) "Save skill" else "Done")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                editingCustomTrigger = null
                                currentSubView = SkillsSubView.MAIN
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Cancel")
                        }
                    }

                    // On the New skill screen, include the client-intake example and "How skills work" notes per F-62 §3
                    if (isNew) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SettingsDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "An example",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))

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
                                    text = "Add \"Client intake sheet\" from the library",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            editingCustomTrigger = null
                                            currentSubView = SkillsSubView.MAIN
                                            libraryTargetId = "intake"
                                            showLibraryDialog = true
                                        }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "How skills work",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
                            Text(
                                text = "• Say a skill's catchphrase anywhere while dictating: everything else you said is what it works on",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                            Text(
                                text = "• Or highlight text first, then say just the catchphrase",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                            Text(
                                text = "• Mix them as you like: one skill can be \"prompt intake\", another \"skill formal email\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                            Text(
                                text = "• A skill uses the AI set up in AI Editing. A stronger AI can do harder jobs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }

                        Text(
                            text = "Looking for mean mode? It's a skill in the library, under Fun.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 24.dp)
                        )
                    }
                }
            }
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
            title = { Text("Add a catchphrase") },
            text = {
                Column {
                    OutlinedTextField(
                        value = phraseText,
                        onValueChange = {
                            phraseText = it
                            errorMessage = null
                        },
                        label = { Text("Catchphrase (2-60 characters)") },
                        placeholder = { Text("e.g. prompt AI") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "What does this catchphrase do?",
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
                        Text("AI edit")
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
                        Text("End of instruction")
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
                            errorMessage = "Catchphrase must be 2-60 characters and cannot contain commas"
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
            title = { Text("Edit catchphrase") },
            text = {
                Column {
                    OutlinedTextField(
                        value = phraseText,
                        onValueChange = {
                            phraseText = it
                            errorMessage = null
                        },
                        label = { Text("Catchphrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "What does this catchphrase do?",
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
                        Text("AI edit")
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
                        Text("End of instruction")
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
                            errorMessage = "Catchphrase must be 2-60 characters and cannot contain commas"
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

    // Browse Skill Library Dialog
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
                if (isLocalSkillsMode) {
                    val existingById = customTriggers.firstOrNull { it.id == newTrigger.id || it.libraryId == newTrigger.libraryId }
                    val validationError = com.example.whisperremote.util.LocalSkillEngine.validateSkill(
                        name = newTrigger.name,
                        phrases = newTrigger.phrases,
                        prompt = newTrigger.prompt,
                        existingSkills = customTriggers,
                        editingId = existingById?.id
                    )
                    if (validationError != null) {
                        coroutineScope.launch { snackbarHostState.showSnackbar(validationError) }
                        return@LibraryBrowserDialog
                    }
                    val nextList = if (existingById != null) {
                        customTriggers.map { if (it.id == existingById.id) newTrigger.copy(id = existingById.id) else it }
                    } else {
                        customTriggers + newTrigger
                    }
                    customTriggers = nextList
                    repository.saveLocalSkills(nextList)
                } else {
                    apiClient.modifyCustomTriggers(set = listOf(newTrigger)) { res ->
                        if (res is CustomTriggersResult.Success) {
                            customTriggers = res.triggers
                        }
                    }
                }
            }
        )
    }
}
