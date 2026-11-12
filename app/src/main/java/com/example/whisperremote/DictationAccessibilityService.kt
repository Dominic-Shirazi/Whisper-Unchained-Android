// This file provides the Android Accessibility Service for Whisper Unchained.
// It is activated by the user in Android Accessibility settings and monitored by FloatingButtonService.
// Inputs: AccessibilityEvents (view focus, window state changes) detecting editable text fields.
// Outputs: Injects transcribed text directly into focused text fields using ACTION_PASTE or ACTION_SET_TEXT, with clipboard fallback.
// It detects focused input nodes and signals FloatingButtonService to show/hide the floating dictation button.

package com.example.whisperremote

// Reason for edit: Added isEnabled helper checking Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES per contract F-16.
// Reason for edit: Splice text at cursor with ACTION_SET_SELECTION restore (never clobbering field content), and prune unused event types per contract F-12.
// Reason for edit: Added sensitive password and payment field detection, skipping insertion and reading per Play policy and F-22.
// Reason for edit: Gate dictated text log behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9.
// Reason for edit: Updated product name to Whisper Unchained and added file documentation header.

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: DictationAccessibilityService? = null

        fun getInstance(): DictationAccessibilityService? {
            Log.d("DictationService", "Getting instance: ${instance != null}")
            return instance
        }

        // Reason for edit: Detect enabled status via Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES per contract F-16
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val expectedComponent = ComponentName(context, DictationAccessibilityService::class.java).flattenToString()
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.equals(expectedComponent, ignoreCase = true) ||
                        componentName.contains(context.packageName + "/" + DictationAccessibilityService::class.java.name) ||
                        componentName.contains("DictationAccessibilityService", ignoreCase = true)
                    ) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var hasFocusedEditable = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DictationService", "Service connected and instance set")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Reason for edit: Restricted to minimal focus and window events per contract F-12
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkFocusState(event)
            }
        }
    }

    private fun checkFocusState(event: AccessibilityEvent) {
        val inputFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val source = event.source

        val activeInput = when {
            inputFocus != null && isEditableNode(inputFocus) -> inputFocus
            source != null && source.isFocused && isEditableNode(source) -> source
            else -> findEditableInTree(inputFocus)
                ?: findEditableInTree(source)
                ?: findEditableInTree(rootInActiveWindow)
        }

        if (activeInput != null && isEditableNode(activeInput)) {
            @Suppress("DEPRECATION")
            focusedNode = AccessibilityNodeInfo.obtain(activeInput)
            if (!hasFocusedEditable) {
                hasFocusedEditable = true
                FloatingButtonService.getInstance()?.onTextFieldFocused()
                Log.d("DictationService", "Editable field focused: ${activeInput.className}")
            }
        } else {
            if (hasFocusedEditable) {
                hasFocusedEditable = false
                FloatingButtonService.getInstance()?.onTextFieldFocusLost()
                Log.d("DictationService", "Focus left editable field")
            }
        }
    }

    private fun isSensitiveNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isPassword) return true
        val inputType = node.inputType
        val textClass = (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT
        val numberClass = (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER
        val textVariation = inputType and android.text.InputType.TYPE_MASK_VARIATION

        if (textClass) {
            if (textVariation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                textVariation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                textVariation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) return true
        }
        if (numberClass) {
            if (textVariation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        }

        val id = node.viewIdResourceName?.lowercase() ?: ""
        if (id.contains("password") || id.contains("pin") || id.contains("creditcard") || id.contains("card_number") || id.contains("cvv") || id.contains("cvc")) {
            return true
        }
        return false
    }

    private fun isEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true)) return true

        // Check if node supports text input actions (Jetpack Compose / WebView / Custom inputs)
        val actions = node.actionList
        for (action in actions) {
            if (action.id == AccessibilityNodeInfo.ACTION_SET_TEXT || action.id == AccessibilityNodeInfo.ACTION_PASTE) {
                return true
            }
        }
        return false
    }

    private fun findEditableInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && isEditableNode(node)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableInTree(child)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {}

    fun pasteText(text: String) {
        // Reason for edit: Gate dictated text log behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9
        if (BuildConfig.DEBUG) {
            Log.d("DictationService", "pasteText called with: $text")
        } else {
            Log.d("DictationService", "pasteText called with ${text.length} chars")
        }

        // 1. Always copy to clipboard as a reliable backup
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Whisper Unchained", text)
            clipboard.setPrimaryClip(clip)
            Log.d("DictationService", "Copied to clipboard")
        } catch (e: Exception) {
            Log.e("DictationService", "Clipboard copy failed", e)
        }

        // 2. Try to find the currently focused editable field
        // Strategy: current focus -> tracked node -> root focus -> tree search
        val targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: focusedNode
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableInTree(rootInActiveWindow)

        Log.d("DictationService", "Target node found: ${targetNode != null}")

        if (targetNode != null && isSensitiveNode(targetNode)) {
            Log.w("DictationService", "Sensitive (password/payment) field detected. Refusing to read or insert; falling back to clipboard per Play policy.")
            FloatingButtonService.getInstance()?.showTemporaryHint("Password field protected: copied to clipboard")
            FloatingButtonService.getInstance()?.onPasteComplete(hasFocusedEditable)
            return
        }

        targetNode?.let { node ->
            if (isEditableNode(node)) {
                // Try PASTE first (inserts at cursor)
                val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d("DictationService", "ACTION_PASTE result: $pasteSuccess")

                if (!pasteSuccess) {
                    // Reason for edit: Safe cursor-aware insertion per contract F-12 (never overwrite field content).
                    val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.isShowingHintText
                    } else false

                    val existingText = if (isShowingHint) "" else (node.text?.toString() ?: "")
                    val selStart = node.textSelectionStart
                    val selEnd = node.textSelectionEnd

                    var inserted = false
                    // Verify that text and selection boundaries can be read reliably
                    if (selStart >= 0 && selEnd >= selStart && selEnd <= existingText.length) {
                        val newText = existingText.substring(0, selStart) + text + existingText.substring(selEnd)
                        val arguments = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText
                            )
                        }
                        val setSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        Log.d("DictationService", "ACTION_SET_TEXT (splice) result: $setSuccess")
                        if (setSuccess) {
                            inserted = true
                            val newCursor = selStart + text.length
                            val selArgs = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                        }
                    }

                    if (!inserted) {
                        Log.w("DictationService", "Could not reliably splice text into field without data loss. Leaving on clipboard.")
                        FloatingButtonService.getInstance()?.showTemporaryHint("Copied - long-press the box to paste")
                    }
                }
            } else {
                Log.e("DictationService", "Node is not editable")
                FloatingButtonService.getInstance()?.showTemporaryHint("Copied - long-press the box to paste")
            }
        } ?: run {
            Log.e("DictationService", "No target node found for pasting")
            FloatingButtonService.getInstance()?.showTemporaryHint("Copied - long-press the box to paste")
        }

        // Notify FloatingButtonService that paste is done
        FloatingButtonService.getInstance()?.onPasteComplete(hasFocusedEditable)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d("DictationService", "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}