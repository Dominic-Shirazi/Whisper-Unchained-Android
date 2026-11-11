// This file is the primary settings activity and permission manager for Whisper Unchained.
// It is launched by the user from the app launcher, floating button settings shortcut, or quick settings tile.
// Inputs: User configurations (provider, endpoint, API key, model selection, prompt hints, name corrections, presets, injection mode, and behavior flags),
//         permissions granted by the Android OS (record audio, notifications, overlay window, media projection),
//         camera QR code scanning via Google Code Scanner (GmsBarcodeScanning).
// Outputs: Persists values directly to SettingsRepository with Android-standard auto-save, coordinates FloatingButtonService, and tests server connectivity.
// It features an authentic Samsung One UI dark theme design mirroring the desktop app with 4 tabs:
// Transcription, Words & Triggers, AI Editing, and Phone.

package com.example.whisperremote

// Reason for edit: Updated Conciseness spinner wording to "Full length | Shorter | Shortest" per contract F-8.

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.whisperremote.data.ChatProviderPreset
import com.example.whisperremote.data.InjectionMode
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.util.TranscriptSanitizer
import com.example.whisperremote.util.VocabularySyncHelper
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : AppCompatActivity() {

    private lateinit var repository: SettingsRepository
    private lateinit var apiClient: ApiClient

    // Tabs & Containers
    private lateinit var tabLayout: TabLayout
    private lateinit var tabTranscriptionContainer: View
    private lateinit var tabWordsTriggersContainer: View
    private lateinit var tabAiEditingContainer: View
    private lateinit var tabPhoneContainer: View

    // Tab 1: Transcription Views
    private lateinit var providerSpinner: Spinner
    private lateinit var apiUrlEditText: EditText
    private lateinit var apiKeyEditText: EditText
    private lateinit var selfHostedModelContainer: View
    private lateinit var selfHostedModelTextView: TextView
    private lateinit var cloudModelSpinner: Spinner
    private lateinit var modelNameEditText: EditText
    private lateinit var testConnectionButton: Button

    // Tab 2: Words & Triggers Views
    private lateinit var promptHintsEditText: EditText
    private lateinit var vocabBudgetTextView: TextView
    private lateinit var fromNameCorrectionsContainer: View
    private lateinit var fromNameCorrectionsTextView: TextView
    private lateinit var syncVocabButton: Button
    private lateinit var selfHostedCorrectionsTextView: TextView
    private lateinit var selfHostedCorrectionsNote: TextView
    private lateinit var localNameCorrectionsEditText: EditText

    // Tab 3: AI Editing Views
    private lateinit var cleanupSwitch: SwitchMaterial
    private lateinit var cleanupStylesLayout: View
    private lateinit var cleanupPunctuationSpinner: Spinner
    private lateinit var cleanupRewriteSpinner: Spinner
    private lateinit var cleanupToneContainer: View
    private lateinit var cleanupToneSpinner: Spinner
    private lateinit var cleanupShortenSpinner: Spinner
    private lateinit var cleanupEmojiSpinner: Spinner
    private lateinit var cloudLlmCard: View
    private lateinit var chatPresetSpinner: Spinner
    private lateinit var chatUrlEditText: EditText
    private lateinit var chatKeyEditText: EditText
    private lateinit var chatModelEditText: EditText

    // Tab 4: Phone (Permissions & System) Views
    private lateinit var injectionModeRadioGroup: RadioGroup
    private lateinit var injectionAccessibility: RadioButton
    private lateinit var injectionClipboard: RadioButton
    private lateinit var openAccessibilitySettingsButton: Button
    private lateinit var openOverlayPermissionButton: Button
    private lateinit var enableInternalAudioButton: Button
    private lateinit var autoTrimHintsSwitch: SwitchMaterial
    private lateinit var startOnBootSwitch: SwitchMaterial
    private lateinit var autoRestartTailscaleSwitch: SwitchMaterial

    private var isSpinnerInitializing = true

    // Model presets per provider (contracts/04 §4)
    private val groqModels = listOf(
        "whisper-large-v3-turbo (Recommended)" to "whisper-large-v3-turbo",
        "whisper-large-v3 (Accurate)" to "whisper-large-v3",
        "Custom model name..." to ""
    )

    private val openAiModels = listOf(
        "whisper-1 (Recommended)" to "whisper-1",
        "gpt-4o-mini-transcribe" to "gpt-4o-mini-transcribe",
        "gpt-4o-transcribe" to "gpt-4o-transcribe",
        "Custom model name..." to ""
    )

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val service = FloatingButtonService.getInstance()
            if (service != null) {
                val success = service.setMediaProjectionData(result.resultCode, result.data!!)
                if (success) {
                    Toast.makeText(this, "Internal audio capture enabled!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to enable internal audio capture", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Floating button service not running", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Internal audio capture permission declined", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissions[Manifest.permission.POST_NOTIFICATIONS] == true)
        ) {
            checkOverlayPermission(false)
        } else {
            Toast.makeText(this, "Microphone & Notification permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = SettingsRepository(this)
        apiClient = ApiClient(this)

        initViews()
        setupTabs()
        loadSettingsIntoUi()
        setupListeners()
        checkPermissions()
    }

    override fun onPause() {
        super.onPause()
        // Enforce auto-save onPause so any background transition instantly persists
        autoSaveSettings()
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.settingsTabLayout)
        tabTranscriptionContainer = findViewById(R.id.tabTranscriptionContainer)
        tabWordsTriggersContainer = findViewById(R.id.tabWordsTriggersContainer)
        tabAiEditingContainer = findViewById(R.id.tabAiEditingContainer)
        tabPhoneContainer = findViewById(R.id.tabPhoneContainer)

        // Tab 1 Views (Transcription)
        providerSpinner = findViewById(R.id.providerSpinner)
        apiUrlEditText = findViewById(R.id.apiUrlEditText)
        apiKeyEditText = findViewById(R.id.apiKeyEditText)
        selfHostedModelContainer = findViewById(R.id.selfHostedModelContainer)
        selfHostedModelTextView = findViewById(R.id.selfHostedModelTextView)
        cloudModelSpinner = findViewById(R.id.cloudModelSpinner)
        modelNameEditText = findViewById(R.id.modelNameEditText)
        testConnectionButton = findViewById(R.id.testConnectionButton)

        // Tab 2 Views (Words & Triggers)
        promptHintsEditText = findViewById(R.id.promptHintsEditText)
        vocabBudgetTextView = findViewById(R.id.vocabBudgetTextView)
        fromNameCorrectionsContainer = findViewById(R.id.fromNameCorrectionsContainer)
        fromNameCorrectionsTextView = findViewById(R.id.fromNameCorrectionsTextView)
        syncVocabButton = findViewById(R.id.syncVocabButton)
        selfHostedCorrectionsTextView = findViewById(R.id.selfHostedCorrectionsTextView)
        selfHostedCorrectionsNote = findViewById(R.id.selfHostedCorrectionsNote)
        localNameCorrectionsEditText = findViewById(R.id.localNameCorrectionsEditText)

        // Tab 3 Views (AI Editing)
        cleanupSwitch = findViewById(R.id.cleanupSwitch)
        cleanupStylesLayout = findViewById(R.id.cleanupStylesLayout)
        cleanupPunctuationSpinner = findViewById(R.id.cleanupPunctuationSpinner)
        cleanupRewriteSpinner = findViewById(R.id.cleanupRewriteSpinner)
        cleanupToneContainer = findViewById(R.id.cleanupToneContainer)
        cleanupToneSpinner = findViewById(R.id.cleanupToneSpinner)
        cleanupShortenSpinner = findViewById(R.id.cleanupShortenSpinner)
        cleanupEmojiSpinner = findViewById(R.id.cleanupEmojiSpinner)
        cloudLlmCard = findViewById(R.id.cloudLlmCard)
        chatPresetSpinner = findViewById(R.id.chatPresetSpinner)
        chatUrlEditText = findViewById(R.id.chatUrlEditText)
        chatKeyEditText = findViewById(R.id.chatKeyEditText)
        chatModelEditText = findViewById(R.id.chatModelEditText)

        // Tab 4 Views (Phone)
        injectionModeRadioGroup = findViewById(R.id.injectionModeRadioGroup)
        injectionAccessibility = findViewById(R.id.injectionAccessibility)
        injectionClipboard = findViewById(R.id.injectionClipboard)
        openAccessibilitySettingsButton = findViewById(R.id.openAccessibilitySettingsButton)
        openOverlayPermissionButton = findViewById(R.id.openOverlayPermissionButton)
        enableInternalAudioButton = findViewById(R.id.enableInternalAudioButton)
        autoTrimHintsSwitch = findViewById(R.id.autoTrimHintsSwitch)
        startOnBootSwitch = findViewById(R.id.startOnBootSwitch)
        autoRestartTailscaleSwitch = findViewById(R.id.autoRestartTailscaleSwitch)

        // Populate Provider Presets Spinner
        val presetNames = ProviderPreset.values().map { it.displayName }
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetNames)

        // Populate Style Spinners
        cleanupPunctuationSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("standard", "light", "minimal")
        )
        cleanupRewriteSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("keep", "fix", "polish")
        )
        cleanupToneSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("neutral", "professional", "casual", "friendly")
        )
        val shortenOptions = listOf(
            SpinnerOption("off", "Full length"),
            SpinnerOption("light", "Shorter"),
            SpinnerOption("strong", "Shortest")
        )
        cleanupShortenSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            shortenOptions
        )
        cleanupEmojiSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("off", "on")
        )

        // Populate Chat Presets Spinner
        val chatPresetNames = ChatProviderPreset.values().map { it.displayName }
        chatPresetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chatPresetNames)
    }

    private fun setupTabs() {
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_transcription))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_words_triggers))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_ai_editing))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_phone))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        tabTranscriptionContainer.visibility = View.VISIBLE
                        tabWordsTriggersContainer.visibility = View.GONE
                        tabAiEditingContainer.visibility = View.GONE
                        tabPhoneContainer.visibility = View.GONE
                    }
                    1 -> {
                        tabTranscriptionContainer.visibility = View.GONE
                        tabWordsTriggersContainer.visibility = View.VISIBLE
                        tabAiEditingContainer.visibility = View.GONE
                        tabPhoneContainer.visibility = View.GONE
                    }
                    2 -> {
                        tabTranscriptionContainer.visibility = View.GONE
                        tabWordsTriggersContainer.visibility = View.GONE
                        tabAiEditingContainer.visibility = View.VISIBLE
                        tabPhoneContainer.visibility = View.GONE
                    }
                    3 -> {
                        tabTranscriptionContainer.visibility = View.GONE
                        tabWordsTriggersContainer.visibility = View.GONE
                        tabAiEditingContainer.visibility = View.GONE
                        tabPhoneContainer.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadSettingsIntoUi() {
        isSpinnerInitializing = true

        // Tab 1 Settings
        apiUrlEditText.setText(repository.baseUrl)
        apiKeyEditText.setText(repository.apiKey)
        modelNameEditText.setText(repository.modelName)

        val presetIndex = ProviderPreset.values().indexOf(repository.providerPreset)
        if (presetIndex >= 0) {
            providerSpinner.setSelection(presetIndex)
        }
        updateModelUiForPreset(repository.providerPreset)

        // Tab 2 Settings
        promptHintsEditText.setText(repository.promptHints)
        updateVocabularyBudget(repository.promptHints)
        localNameCorrectionsEditText.setText(repository.localNameCorrections)

        // Tab 3 Settings
        cleanupSwitch.isChecked = repository.cleanupEnabled
        cleanupStylesLayout.visibility = if (repository.cleanupEnabled) View.VISIBLE else View.GONE

        setSpinnerToValue(cleanupPunctuationSpinner, repository.cleanupPunctuation)
        setSpinnerToValue(cleanupRewriteSpinner, repository.cleanupRewrite)
        setSpinnerToValue(cleanupToneSpinner, repository.cleanupTone)
        setSpinnerToValue(cleanupShortenSpinner, repository.cleanupShorten)
        setSpinnerToValue(cleanupEmojiSpinner, repository.cleanupEmoji)
        updateToneState()

        val chatPresetIndex = ChatProviderPreset.values().indexOf(repository.cleanupChatPreset)
        if (chatPresetIndex >= 0) {
            chatPresetSpinner.setSelection(chatPresetIndex)
        }
        chatUrlEditText.setText(repository.cleanupChatUrl)
        chatKeyEditText.setText(repository.cleanupChatKey)
        chatModelEditText.setText(repository.cleanupChatModel)

        // Tab 4 Settings
        autoTrimHintsSwitch.isChecked = repository.autoTrimHints
        startOnBootSwitch.isChecked = repository.startOnBoot
        autoRestartTailscaleSwitch.isChecked = repository.autoRestartTailscale

        when (repository.injectionMode) {
            InjectionMode.ACCESSIBILITY -> injectionAccessibility.isChecked = true
            InjectionMode.CLIPBOARD -> injectionClipboard.isChecked = true
        }

        updateDynamicVisibility()
        isSpinnerInitializing = false

        // If self-hosted, attempt a background health/vocab fetch to update model & corrections
        if (repository.providerPreset == ProviderPreset.SELF_HOSTED && repository.baseUrl.isNotBlank()) {
            fetchSelfHostedDetails()
        }
    }

    private fun updateModelUiForPreset(preset: ProviderPreset) {
        when (preset) {
            ProviderPreset.SELF_HOSTED -> {
                selfHostedModelContainer.visibility = View.VISIBLE
                cloudModelSpinner.visibility = View.GONE
                modelNameEditText.visibility = View.GONE
                val currentModel = repository.modelName
                selfHostedModelTextView.text = if (currentModel.isNotBlank()) {
                    "Your PC runs $currentModel"
                } else {
                    "Your PC runs large-v3-turbo"
                }
            }
            ProviderPreset.GROQ -> {
                selfHostedModelContainer.visibility = View.GONE
                cloudModelSpinner.visibility = View.VISIBLE
                val labels = groqModels.map { it.first }
                cloudModelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

                val currentModel = repository.modelName
                val matchIdx = groqModels.indexOfFirst { it.second == currentModel }
                if (matchIdx >= 0) {
                    cloudModelSpinner.setSelection(matchIdx)
                    modelNameEditText.visibility = View.GONE
                } else {
                    // Custom model name
                    cloudModelSpinner.setSelection(groqModels.size - 1)
                    modelNameEditText.visibility = View.VISIBLE
                }
            }
            ProviderPreset.OPENROUTER_OPENAI -> {
                selfHostedModelContainer.visibility = View.GONE
                cloudModelSpinner.visibility = View.VISIBLE
                val labels = openAiModels.map { it.first }
                cloudModelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

                val currentModel = repository.modelName
                val matchIdx = openAiModels.indexOfFirst { it.second == currentModel }
                if (matchIdx >= 0) {
                    cloudModelSpinner.setSelection(matchIdx)
                    modelNameEditText.visibility = View.GONE
                } else {
                    // Custom model name
                    cloudModelSpinner.setSelection(openAiModels.size - 1)
                    modelNameEditText.visibility = View.VISIBLE
                }
            }
            ProviderPreset.CUSTOM -> {
                selfHostedModelContainer.visibility = View.GONE
                cloudModelSpinner.visibility = View.GONE
                modelNameEditText.visibility = View.VISIBLE
            }
        }
    }

    data class SpinnerOption(val value: String, val display: String) {
        override fun toString(): String = display
    }

    private fun setSpinnerToValue(spinner: Spinner, value: String) {
        val adapter = spinner.adapter ?: return
        for (i in 0 until adapter.count) {
            val item = adapter.getItem(i)
            val itemVal = if (item is SpinnerOption) item.value else item?.toString()
            if (itemVal == value) {
                spinner.setSelection(i)
                break
            }
        }
    }

    private fun updateDynamicVisibility() {
        val isSelfHosted = repository.providerPreset == ProviderPreset.SELF_HOSTED
        syncVocabButton.visibility = if (isSelfHosted) View.VISIBLE else View.GONE
        cloudLlmCard.visibility = if (!isSelfHosted && repository.cleanupEnabled) View.VISIBLE else View.GONE

        // Words & Triggers visibility per preset
        selfHostedCorrectionsTextView.visibility = if (isSelfHosted) View.VISIBLE else View.GONE
        selfHostedCorrectionsNote.visibility = if (isSelfHosted) View.VISIBLE else View.GONE
        localNameCorrectionsEditText.visibility = if (!isSelfHosted) View.VISIBLE else View.GONE
    }

    private fun updateVocabularyBudget(text: String) {
        val count = text.length
        vocabBudgetTextView.text = getString(R.string.char_budget_format, count, TranscriptSanitizer.MAX_HINT_CHARS)
        if (count > TranscriptSanitizer.MAX_HINT_CHARS) {
            vocabBudgetTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            vocabBudgetTextView.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_secondary))
        }
    }

    private fun updateToneState() {
        val isKeep = repository.cleanupRewrite == "keep"
        cleanupToneSpinner.isEnabled = !isKeep
        cleanupToneContainer.alpha = if (isKeep) 0.5f else 1.0f
        if (isKeep) {
            setSpinnerToValue(cleanupToneSpinner, "neutral")
        }
    }

    private fun setupListeners() {
        // Text changes auto-save immediately
        apiUrlEditText.doAfterTextChanged { autoSaveSettings() }
        apiKeyEditText.doAfterTextChanged { autoSaveSettings() }
        modelNameEditText.doAfterTextChanged { autoSaveSettings() }
        localNameCorrectionsEditText.doAfterTextChanged { autoSaveSettings() }

        promptHintsEditText.doAfterTextChanged { s ->
            updateVocabularyBudget(s?.toString().orEmpty())
            autoSaveSettings()
        }

        // Provider Preset Selection
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                val selectedPreset = ProviderPreset.values()[position]
                repository.providerPreset = selectedPreset
                if (selectedPreset == ProviderPreset.GROQ || selectedPreset == ProviderPreset.OPENROUTER_OPENAI) {
                    if (selectedPreset.defaultBaseUrl.isNotBlank()) {
                        apiUrlEditText.setText(selectedPreset.defaultBaseUrl)
                    }
                    if (selectedPreset.defaultModel.isNotBlank()) {
                        repository.modelName = selectedPreset.defaultModel
                        modelNameEditText.setText(selectedPreset.defaultModel)
                    }
                }
                updateModelUiForPreset(selectedPreset)
                updateDynamicVisibility()
                autoSaveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Cloud Model Spinner Selection
        cloudModelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                val activeList = when (repository.providerPreset) {
                    ProviderPreset.GROQ -> groqModels
                    ProviderPreset.OPENROUTER_OPENAI -> openAiModels
                    else -> emptyList()
                }
                if (position in activeList.indices) {
                    val pair = activeList[position]
                    if (pair.second.isNotBlank()) {
                        modelNameEditText.visibility = View.GONE
                        modelNameEditText.setText(pair.second)
                        repository.modelName = pair.second
                    } else {
                        // Custom choice
                        modelNameEditText.visibility = View.VISIBLE
                    }
                    autoSaveSettings()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Top Hero Card & Scan Now Pill Button
        findViewById<View>(R.id.scanPairingHeroCard).setOnClickListener { launchQrScanner() }
        findViewById<Button>(R.id.scanNowButton).setOnClickListener { launchQrScanner() }
        findViewById<Button>(R.id.pasteCodeManuallyButton).setOnClickListener { showManualPasteDialog() }

        // Test Connection Button (contracts/04 §5)
        testConnectionButton.setOnClickListener {
            autoSaveSettings()
            Toast.makeText(this, "Testing connection...", Toast.LENGTH_SHORT).show()
            if (repository.providerPreset == ProviderPreset.SELF_HOSTED) {
                apiClient.checkHealth { result ->
                    runOnUiThread {
                        when (result) {
                            is HealthResult.Ok -> {
                                val model = result.model ?: "large-v3-turbo"
                                selfHostedModelTextView.text = "Your PC runs $model"
                                repository.modelName = model
                                AlertDialog.Builder(this)
                                    .setTitle("Connected")
                                    .setMessage("Connected: your PC runs $model")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            is HealthResult.Paused -> {
                                val reason = result.reason
                                val msg = if (!reason.isNullOrBlank()) "Paused on your PC ($reason)" else "Paused on your PC"
                                AlertDialog.Builder(this)
                                    .setTitle("Server Paused")
                                    .setMessage(msg)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            is HealthResult.Unreachable -> {
                                AlertDialog.Builder(this)
                                    .setTitle("Connection Failed")
                                    .setMessage("Can't reach it: is Tailscale on?\n\nDetails: ${result.error.localizedMessage}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            is HealthResult.ServiceError -> {
                                AlertDialog.Builder(this)
                                    .setTitle("Server Error")
                                    .setMessage(result.message)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
            } else {
                apiClient.testConnection { success, message ->
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle(if (success) "Connection Successful" else "Connection Failed")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }

        // Sync Shared Vocabulary Button (Tab 2)
        syncVocabButton.setOnClickListener {
            Toast.makeText(this, "Syncing vocabulary with server...", Toast.LENGTH_SHORT).show()
            fetchSelfHostedDetails(showToast = true)
        }

        // Tab 3 Listeners (AI Editing)
        cleanupSwitch.setOnCheckedChangeListener { _, isChecked ->
            repository.cleanupEnabled = isChecked
            cleanupStylesLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateDynamicVisibility()
        }

        cleanupPunctuationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                repository.cleanupPunctuation = parent?.getItemAtPosition(position) as? String ?: "standard"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cleanupRewriteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                repository.cleanupRewrite = parent?.getItemAtPosition(position) as? String ?: "keep"
                updateToneState()
                autoSaveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cleanupToneSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                repository.cleanupTone = parent?.getItemAtPosition(position) as? String ?: "neutral"
                autoSaveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cleanupShortenSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                val item = parent?.getItemAtPosition(position)
                val value = if (item is SpinnerOption) item.value else item?.toString() ?: "off"
                repository.cleanupShorten = value
                autoSaveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cleanupEmojiSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                repository.cleanupEmoji = parent?.getItemAtPosition(position) as? String ?: "off"
                autoSaveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        chatPresetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitializing) return
                val preset = ChatProviderPreset.values()[position]
                repository.cleanupChatPreset = preset
                if (preset != ChatProviderPreset.CUSTOM) {
                    chatUrlEditText.setText(preset.defaultUrl)
                    chatModelEditText.setText(preset.defaultModel)
                    autoSaveSettings()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        chatUrlEditText.doAfterTextChanged { autoSaveSettings() }
        chatKeyEditText.doAfterTextChanged { autoSaveSettings() }
        chatModelEditText.doAfterTextChanged { autoSaveSettings() }

        // Tab 4 Listeners (Phone)
        injectionModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            repository.injectionMode = if (checkedId == R.id.injectionClipboard) {
                InjectionMode.CLIPBOARD
            } else {
                InjectionMode.ACCESSIBILITY
            }
        }

        openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        openOverlayPermissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        enableInternalAudioButton.setOnClickListener {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        autoTrimHintsSwitch.setOnCheckedChangeListener { _, isChecked ->
            repository.autoTrimHints = isChecked
        }
        startOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            repository.startOnBoot = isChecked
        }
        autoRestartTailscaleSwitch.setOnCheckedChangeListener { _, isChecked ->
            repository.autoRestartTailscale = isChecked
        }
    }

    /**
     * Fetches health and executes true two-way vocabulary sync with the desktop server.
     * Deduplicates existing words and pushes newly added local words to POST /v1/vocabulary.
     */
    private fun fetchSelfHostedDetails(showToast: Boolean = false) {
        apiClient.checkHealth { health ->
            if (health is HealthResult.Ok && !health.model.isNullOrBlank()) {
                runOnUiThread {
                    selfHostedModelTextView.text = "Your PC runs ${health.model}"
                    repository.modelName = health.model
                }
            }
        }

        syncVocabularyWithServer(showToast)
    }

    private fun syncVocabularyWithServer(showToast: Boolean) {
        val currentLocalText = promptHintsEditText.text.toString()
        val localWords = VocabularySyncHelper.parseWords(currentLocalText)

        apiClient.getVocabulary { result ->
            runOnUiThread {
                when (result) {
                    is VocabularyResult.Success -> {
                        val serverVocab = result.data.vocabulary
                        val fromNameCorrections = result.data.fromNameCorrections
                        val newWordsToPush = VocabularySyncHelper.findNewWords(
                            localWords = localWords,
                            serverVocab = serverVocab,
                            fromNameCorrections = fromNameCorrections
                        )

                        if (newWordsToPush.isNotEmpty()) {
                            // Push newly typed words to server
                            apiClient.addVocabulary(newWordsToPush) { addResult ->
                                runOnUiThread {
                                    when (addResult) {
                                        is VocabularyResult.Success -> {
                                            updateVocabUiFromData(addResult.data)
                                            if (showToast) {
                                                val pushedCount = addResult.data.added.size
                                                val totalCount = addResult.data.vocabulary.size
                                                Toast.makeText(
                                                    this,
                                                    "Synced! Pushed $pushedCount new word(s) to server ($totalCount total)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        is VocabularyResult.Error -> {
                                            // Fallback: merge locally so user never loses their typed words
                                            val merged = VocabularySyncHelper.mergeAndDeduplicate(serverVocab, localWords)
                                            promptHintsEditText.setText(VocabularySyncHelper.formatForDisplay(merged))
                                            updateCorrectionsUi(result.data)
                                            autoSaveSettings()
                                            if (showToast) {
                                                Toast.makeText(
                                                    this,
                                                    "Words merged locally, but server push failed: ${addResult.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                        is VocabularyResult.NetworkError -> {
                                            val merged = VocabularySyncHelper.mergeAndDeduplicate(serverVocab, localWords)
                                            promptHintsEditText.setText(VocabularySyncHelper.formatForDisplay(merged))
                                            updateCorrectionsUi(result.data)
                                            autoSaveSettings()
                                            if (showToast) {
                                                Toast.makeText(
                                                    this,
                                                    "Words merged locally, but server was unreachable to save additions",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // No new local words to push; server vocabulary is complete
                            updateVocabUiFromData(result.data)
                            if (showToast) {
                                Toast.makeText(
                                    this,
                                    "Synced with server (${result.data.vocabulary.size} words)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    is VocabularyResult.Error -> {
                        if (showToast) {
                            Toast.makeText(this, "Vocabulary sync failed: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    is VocabularyResult.NetworkError -> {
                        if (showToast) {
                            Toast.makeText(this, "Server unreachable for vocabulary sync. Local words kept.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun updateVocabUiFromData(data: VocabularyData) {
        val vocabWords = VocabularySyncHelper.formatForDisplay(data.vocabulary)
        promptHintsEditText.setText(vocabWords)
        updateCorrectionsUi(data)
        autoSaveSettings()
    }

    private fun updateCorrectionsUi(data: VocabularyData) {
        if (data.fromNameCorrections.isNotEmpty()) {
            fromNameCorrectionsContainer.visibility = View.VISIBLE
            fromNameCorrectionsTextView.text = data.fromNameCorrections.joinToString(", ")
        } else {
            fromNameCorrectionsContainer.visibility = View.GONE
        }

        if (data.nameCorrections.isNotEmpty()) {
            val formatted = data.nameCorrections.entries.joinToString("\n") { (k, v) ->
                "$k → $v"
            }
            selfHostedCorrectionsTextView.text = formatted
        } else {
            selfHostedCorrectionsTextView.text = "None configured"
        }
    }

    /**
     * Automatically saves all current UI field values directly to persistent preferences.
     */
    private fun autoSaveSettings() {
        val url = apiUrlEditText.text.toString().trim()
        repository.baseUrl = url
        repository.apiKey = apiKeyEditText.text.toString().trim()

        if (repository.providerPreset != ProviderPreset.SELF_HOSTED) {
            val model = modelNameEditText.text.toString().trim()
            if (model.isNotBlank()) {
                repository.modelName = model
            }
        }

        repository.promptHints = promptHintsEditText.text.toString().trim()
        repository.localNameCorrections = localNameCorrectionsEditText.text.toString().trim()

        repository.cleanupTone = cleanupToneSpinner.selectedItem?.toString() ?: "neutral"
        repository.cleanupChatUrl = chatUrlEditText.text.toString().trim()
        repository.cleanupChatKey = chatKeyEditText.text.toString().trim()
        val chatModel = chatModelEditText.text.toString().trim()
        repository.cleanupChatModel = if (chatModel.isNotBlank()) chatModel else "gpt-4o-mini"
    }

    /**
     * Launches the system Google Code Scanner to scan the Desktop installer/settings QR code.
     * Requires zero camera permissions in the app manifest.
     */
    private fun launchQrScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                Log.d("MainActivity", "Scanned QR code: $raw")
                if (!raw.isNullOrBlank()) {
                    handleImportedPayload(raw)
                } else {
                    Toast.makeText(this, "Empty QR code detected", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnCanceledListener {
                Log.d("MainActivity", "QR scan canceled by user")
            }
            .addOnFailureListener { e ->
                Log.w("MainActivity", "Google Code Scanner failed, offering manual paste", e)
                showManualPasteDialog()
            }
    }

    private fun handleImportedPayload(raw: String) {
        val success = repository.importQrPayload(raw)
        if (success) {
            loadSettingsIntoUi()
            Toast.makeText(this, "Pairing settings imported successfully!", Toast.LENGTH_SHORT).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Invalid Code")
                .setMessage("The scanned QR code is not a valid Whisper Unchained pairing payload.\n\nScanned content: $raw")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showManualPasteDialog() {
        val input = EditText(this).apply {
            hint = "Paste JSON payload e.g. {\"v\":1,\"base_url\":...}"
            textSize = 14f
        }
        AlertDialog.Builder(this)
            .setTitle("Paste Pairing Code")
            .setMessage("Paste the JSON configuration payload shown by your desktop installer or settings:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    handleImportedPayload(raw)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            checkOverlayPermission(false)
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkOverlayPermission(autoFinish: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please enable 'Appear on top' permission", Toast.LENGTH_LONG).show()
        } else {
            startFloatingService(autoFinish)
        }
    }

    private fun startFloatingService(autoFinish: Boolean) {
        val intent = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (autoFinish) finish()
    }
}
