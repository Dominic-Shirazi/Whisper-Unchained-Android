// This file provides the Android Accessibility Service for Whisper Unchained.
// It is activated by the user in Android Accessibility settings and monitored by FloatingButtonService.
// Inputs: AccessibilityEvents (view focus, window state changes) detecting editable text fields.
// Outputs: Injects transcribed text directly into focused text fields using ACTION_PASTE or ACTION_SET_TEXT, with clipboard fallback.
// It detects focused input nodes and signals FloatingButtonService to show/hide the floating dictation button.

package com.example.whisperremote

// Reason for edit: Updated product name to Whisper Unchained and added file documentation header.

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var hasFocusedEditable = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DictationService", "Service connected and instance set")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
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
        Log.d("DictationService", "pasteText called with: $text")

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

        targetNode?.let { node ->
            if (isEditableNode(node)) {
                // Try PASTE first (inserts at cursor)
                val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d("DictationService", "ACTION_PASTE result: $pasteSuccess")

                if (!pasteSuccess) {
                    // Fallback to SET_TEXT (replaces entire field content)
                    val arguments = Bundle()
                    arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                    val setSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    Log.d("DictationService", "ACTION_SET_TEXT result: $setSuccess")
                }
            } else {
                Log.e("DictationService", "Node is not editable")
            }
        } ?: Log.e("DictationService", "No target node found for pasting")

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