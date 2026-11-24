// This file provides the Android Accessibility Service for Whisper Unchained.
// It is activated by the user in Android Accessibility settings and monitored by FloatingButtonService.
// Inputs: AccessibilityEvents (view focus, window state changes) detecting editable text fields.
// Outputs: Injects transcribed text directly into focused text fields using ACTION_PASTE or ACTION_SET_TEXT, with clipboard fallback.
// It detects focused input nodes and signals FloatingButtonService to show/hide the floating dictation button.

package com.example.whisperremote

// Reason for edit: Bound AccessibilityNodeInfo tree walks (node/depth/time budget), skip redundant full-tree IPC walks on main thread, ignore own non-EditText overlay events, and avoid double getSelectedText() IPC calls so the floating button never freezes or ANRs (Build 39).
// Reason for edit: Coalesce TYPE_WINDOW_CONTENT_CHANGED (250ms), ignore own-overlay content-changed events and skip during button drag, fast-path getSelectedText() to avoid redundant IPC tree walks, and add peekRememberedHighlightText() + getImeWindowHeightPx() per F-75.
// Reason for edit: Remember highlighted text, range, and node when selection is detected or read for the Type pill, guard by package/window/node, and use isEditableNode in validateCachedNode so tapping the mic button always captures the same highlight as the Type pill per F-73.
// Reason for edit: Clear cached focusedNode/lastSelectionNode on window change or focus loss, and refresh+verify isFocused && isEditable before using cached nodes on paste per F-68.
// Reason for edit: Refresh node before reading selection, track selection source node for WebView/Gmail editors, prefer ACTION_PASTE when replacing active selection, and add hasRichFormattingInSelection() per F-65.
// Reason for edit: Add getSelectedText() and replaceOrInsertText() for F-54 typed instructions.
// Reason for edit: Add hasFocusedEditable() helper and auto-start FloatingButtonService in onServiceConnected() per F-47 and F-48.
// Reason for edit: Added isEnabled helper checking Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES per contract F-16.
// Reason for edit: Splice text at cursor with ACTION_SET_SELECTION restore (never clobbering field content), and prune unused event types per contract F-12.
// Reason for edit: Added sensitive password and payment field detection, skipping insertion and reading per Play policy and F-22.
// Reason for edit: Gate dictated text log behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9.
// Reason for edit: Updated product name to Whisper Unchained and added file documentation header.

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: DictationAccessibilityService? = null
        internal const val MAX_TREE_NODES = 120
        internal const val MAX_TREE_DEPTH = 12
        internal const val MAX_TREE_WALK_MS = 16L

        fun getInstance(): DictationAccessibilityService? {
            if (BuildConfig.DEBUG) {
                Log.d("DictationService", "Getting instance: ${instance != null}")
            }
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
        // Reason for edit: Add hasFocusedEditable() helper per F-47
        fun hasFocusedEditable(): Boolean {
            return instance?.hasFocusedEditable() ?: false
        }
    }

    // Reason for edit: Remembered highlight snapshot (text, package, windowId, selection range, node) guarded per F-73
    internal data class RememberedHighlight(
        val text: String,
        val packageName: String?,
        val windowId: Int,
        val selStart: Int,
        val selEnd: Int,
        val node: AccessibilityNodeInfo?,
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    internal var focusedNode: AccessibilityNodeInfo? = null
    internal var lastSelectionNode: AccessibilityNodeInfo? = null
    internal var rememberedHighlight: RememberedHighlight? = null
    internal var activeWindowPackage: String? = null
    internal var activeWindowId: Int = -1
    internal var findFocusOverrideForTesting: ((Int) -> AccessibilityNodeInfo?)? = null
    private var hasFocusedEditable = false
    // Reason for edit: Hard budget for synchronous AccessibilityNodeInfo tree walks so main-thread IPC can never freeze the floating button on large/foldable hierarchies (Build 39)
    internal class TreeWalkBudget(
        private val maxNodes: Int = MAX_TREE_NODES,
        private val deadlineUptimeMs: Long = android.os.SystemClock.uptimeMillis() + MAX_TREE_WALK_MS
    ) {
        var visitedNodes: Int = 0
            private set

        fun tryVisit(): Boolean {
            if (visitedNodes >= maxNodes) return false
            if (visitedNodes > 0 && (visitedNodes and 0x0F) == 0 && android.os.SystemClock.uptimeMillis() >= deadlineUptimeMs) {
                return false
            }
            visitedNodes++
            return true
        }
    }

    private fun isOwnNonEditOverlayEvent(event: AccessibilityEvent): Boolean {
        val evtPkg = event.packageName?.toString()
        return evtPkg == packageName && event.className?.toString()?.contains("EditText", ignoreCase = true) != true
    }

    // Reason for edit: Coalesce TYPE_WINDOW_CONTENT_CHANGED work to at most once per 250ms to prevent main-thread IPC storms during button drag per F-75 §2
    private val contentChangedHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingContentChangedRunnable: Runnable? = null
    private var lastContentCheckMs: Long = 0L

    fun peekRememberedHighlightText(): String? = rememberedHighlight?.text?.takeIf { it.isNotBlank() }

    // Reason for edit: Read real IME window height from AccessibilityWindowInfo.TYPE_INPUT_METHOD as fallback when overlay attaches while keyboard is already open per F-75 §3
    fun getImeWindowHeightPx(screenHeight: Int): Int {
        return try {
            val rect = android.graphics.Rect()
            for (win in windows) {
                if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    win.getBoundsInScreen(rect)
                    if (rect.height() > 0 && rect.top in 1 until screenHeight) {
                        return (screenHeight - rect.top).coerceAtLeast(0)
                    }
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    // Reason for edit: Refresh cached node and verify it is still isFocused && isEditableNode before using it per F-68 & F-73
    internal fun validateCachedNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        try {
            node.refresh()
        } catch (_: Exception) {}
        return if (node.isFocused && isEditableNode(node)) node else null
    }

    // Reason for edit: Resolve target node for paste using fresh focus first, then honest validated cached node per F-68
    internal fun resolveTargetNodeForPaste(preferSelectionNode: Boolean = false): AccessibilityNodeInfo? {
        val liveFocus = findFocusOverrideForTesting?.invoke(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (liveFocus != null) {
            try {
                liveFocus.refresh()
            } catch (_: Exception) {}
            if (liveFocus.isFocused && isEditableNode(liveFocus)) {
                return liveFocus
            }
        }

        if (preferSelectionNode) {
            val validSel = validateCachedNode(lastSelectionNode)
            if (validSel != null) {
                return validSel
            } else {
                lastSelectionNode = null
            }
        }

        val validFocused = validateCachedNode(focusedNode)
        if (validFocused != null) {
            return validFocused
        } else {
            focusedNode = null
        }

        if (!preferSelectionNode) {
            val validSel = validateCachedNode(lastSelectionNode)
            if (validSel != null) {
                return validSel
            } else {
                lastSelectionNode = null
            }
        }

        val rootLive = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (rootLive != null) {
            try {
                rootLive.refresh()
            } catch (_: Exception) {}
            if (rootLive.isFocused && isEditableNode(rootLive)) {
                return rootLive
            }
        }

        return findEditableInTree(rootInActiveWindow)
    }

    // Reason for edit: Fresh focus lookup only (no stale tracked node) so the mic hides when focus has actually left per F-47
    fun hasFocusedEditable(): Boolean {
        return try {
            val targetNode = findFocusOverrideForTesting?.invoke(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableInTree(rootInActiveWindow)
            targetNode != null && isEditableNode(targetNode)
        } catch (e: Exception) {
            false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DictationService", "Service connected and instance set")

        // Reason for edit: Auto-start FloatingButtonService when accessibility service connects per F-48 item 3(b)
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingButtonService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("DictationService", "Failed to start FloatingButtonService from accessibility", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Reason for edit: Ignore own non-EditText overlay events across all event types (prevents bringToFront()/pill updates from clobbering activeWindowPackage or triggering IPC storms) (Build 39)
        if (isOwnNonEditOverlayEvent(event)) {
            return
        }

        // Reason for edit: Ignore TYPE_WINDOW_CONTENT_CHANGED during active button drags and coalesce to at most once per 250ms per F-75 §2 & Build 39
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (FloatingButtonService.isDraggingOverlay) {
                return
            }
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = now - lastContentCheckMs
            if (elapsed < 250L) {
                if (pendingContentChangedRunnable == null) {
                    val runnable = Runnable {
                        pendingContentChangedRunnable = null
                        if (!FloatingButtonService.isDraggingOverlay) {
                            lastContentCheckMs = android.os.SystemClock.uptimeMillis()
                            checkFocusState(null, allowRootTreeWalk = hasFocusedEditable)
                        }
                    }
                    pendingContentChangedRunnable = runnable
                    contentChangedHandler.postDelayed(runnable, 250L - elapsed)
                }
                return
            }
            lastContentCheckMs = now
            checkFocusState(event, allowRootTreeWalk = hasFocusedEditable)
            return
        }

        // Reason for edit: Clear cached nodes on window/package change per F-68; keep rememberedHighlight across same-app floating Action Mode toolbar popups per F-73
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val newPkg = event.packageName?.toString()
            val newWinId = event.windowId
            val pkgChanged = newPkg != null && activeWindowPackage != null && newPkg != activeWindowPackage
            val winChanged = newWinId != -1 && activeWindowId != -1 && newWinId != activeWindowId
            if (pkgChanged || winChanged) {
                focusedNode = null
                lastSelectionNode = null
                if (pkgChanged) {
                    rememberedHighlight = null
                }
            }
            if (newPkg != null) activeWindowPackage = newPkg
            if (newWinId != -1) activeWindowId = newWinId
        }

        // Reason for edit: Minimal focus/window events per F-12, plus selection tracking and remembered highlight per F-59, F-73, F-75 §2, and Build 39
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val focusNotifiedButton = checkFocusState(event, allowRootTreeWalk = true)
                if (!focusNotifiedButton && !FloatingButtonService.isDraggingOverlay && hasFocusedEditable) {
                    FloatingButtonService.getInstance()?.onTextSelectionChanged()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (FloatingButtonService.isDraggingOverlay) {
                    return
                }
                event.source?.let { src ->
                    val editableTarget = if (isEditableNode(src)) src else findEditableInTree(src)
                    if (editableTarget != null && !isSensitiveNode(editableTarget) && (editableTarget.isFocused || src.isFocused)) {
                        @Suppress("DEPRECATION")
                        lastSelectionNode = AccessibilityNodeInfo.obtain(editableTarget)
                        val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) editableTarget.isShowingHintText else false
                        val fullText = if (isHint) "" else (editableTarget.text?.toString() ?: src.text?.toString() ?: "")
                        val selStart = if (editableTarget.textSelectionStart >= 0) editableTarget.textSelectionStart else event.fromIndex
                        val selEnd = if (editableTarget.textSelectionEnd >= 0) editableTarget.textSelectionEnd else event.toIndex
                        if (selStart >= 0 && selEnd > selStart && selEnd <= fullText.length) {
                            val selected = fullText.substring(selStart, selEnd)
                            if (selected.isNotBlank()) {
                                @Suppress("DEPRECATION")
                                rememberedHighlight = RememberedHighlight(
                                    text = selected,
                                    packageName = editableTarget.packageName?.toString() ?: activeWindowPackage,
                                    windowId = editableTarget.windowId,
                                    selStart = selStart,
                                    selEnd = selEnd,
                                    node = AccessibilityNodeInfo.obtain(editableTarget)
                                )
                            }
                        } else if (selStart >= 0 && selEnd == selStart) {
                            // Selection collapsed to a cursor in the active text field
                            rememberedHighlight = null
                        }
                    } else if (!src.isFocused || !isEditableNode(src)) {
                        lastSelectionNode = null
                    }
                }
                FloatingButtonService.getInstance()?.onTextSelectionChanged()
            }
        }
    }

    private fun checkFocusState(event: AccessibilityEvent?, allowRootTreeWalk: Boolean = true): Boolean {
        val inputFocus = findFocusOverrideForTesting?.invoke(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val source = event?.source

        val activeInput = when {
            inputFocus != null && isEditableNode(inputFocus) -> inputFocus
            source != null && source.isFocused && isEditableNode(source) -> source
            else -> findEditableInTree(inputFocus)
                ?: findEditableInTree(source)
                ?: if (allowRootTreeWalk) findEditableInTree(rootInActiveWindow) else null
        }

        if (activeInput != null && isEditableNode(activeInput)) {
            @Suppress("DEPRECATION")
            focusedNode = AccessibilityNodeInfo.obtain(activeInput)
            activeInput.packageName?.toString()?.let { activeWindowPackage = it }
            if (activeInput.windowId != -1) activeWindowId = activeInput.windowId
            if (!hasFocusedEditable) {
                hasFocusedEditable = true
                FloatingButtonService.getInstance()?.onTextFieldFocused()
                Log.d("DictationService", "Editable field focused: ${activeInput.className}")
                return true
            }
        } else {
            // Reason for edit: Clear cached nodes when focus leaves or node is no longer focused/editable per F-68 & F-73
            if (validateCachedNode(focusedNode) == null) {
                focusedNode = null
            }
            if (validateCachedNode(lastSelectionNode) == null) {
                lastSelectionNode = null
            }
            if (hasFocusedEditable) {
                hasFocusedEditable = false
                rememberedHighlight = null
                FloatingButtonService.getInstance()?.onTextFieldFocusLost()
                Log.d("DictationService", "Focus left editable field")
                return true
            }
        }
        return false
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

    // Reason for edit: Bound tree traversal by node count, depth, and wall-clock budget to prevent main-thread IPC freezes on large foldable/tablet hierarchies (Build 39)
    internal fun findEditableInTree(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        budget: TreeWalkBudget = TreeWalkBudget()
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH || !budget.tryVisit()) return null
        if (node.isFocused && isEditableNode(node)) return node

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            val result = findEditableInTree(child, depth + 1, budget)
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
        // Strategy: current focus -> refreshed & validated cached node -> root focus -> tree search per F-68
        val targetNode = resolveTargetNodeForPaste(preferSelectionNode = false)

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

    private fun extractSelectionFromNode(node: AccessibilityNodeInfo?, updateRemembered: Boolean = false): String? {
        if (node == null) return null
        // Reason for edit: Snapshot pre-refresh selection in case node.refresh() on a WebView/Compose virtual node clears transient selection indices per F-73
        val preText = node.text?.toString()
        val preStart = node.textSelectionStart
        val preEnd = node.textSelectionEnd
        try {
            node.refresh()
        } catch (_: Exception) {}
        if (isSensitiveNode(node) || !isEditableNode(node)) return null
        val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.isShowingHintText
        } else false
        if (isShowingHint) return null
        val fullText = node.text?.toString() ?: preText ?: return null
        var selStart = node.textSelectionStart
        var selEnd = node.textSelectionEnd
        if ((selStart < 0 || selEnd <= selStart) && preStart >= 0 && preEnd > preStart && preEnd <= fullText.length) {
            selStart = preStart
            selEnd = preEnd
        }
        if (selStart >= 0 && selEnd > selStart && selEnd <= fullText.length) {
            val selected = fullText.substring(selStart, selEnd)
            if (updateRemembered && selected.isNotBlank()) {
                @Suppress("DEPRECATION")
                rememberedHighlight = RememberedHighlight(
                    text = selected,
                    packageName = node.packageName?.toString() ?: activeWindowPackage,
                    windowId = node.windowId,
                    selStart = selStart,
                    selEnd = selEnd,
                    node = AccessibilityNodeInfo.obtain(node)
                )
            }
            return selected
        }
        return null
    }

    // Reason for edit: Bound selection tree traversal by node count, depth, and wall-clock budget (Build 39)
    private fun findEditableWithSelectionInTree(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        budget: TreeWalkBudget = TreeWalkBudget()
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH || !budget.tryVisit()) return null
        if (!isSensitiveNode(node) && node.isFocused && isEditableNode(node)) {
            val fullText = node.text?.toString() ?: ""
            val selStart = node.textSelectionStart
            val selEnd = node.textSelectionEnd
            if (selStart >= 0 && selEnd > selStart && selEnd <= fullText.length) {
                return node
            }
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            val found = findEditableWithSelectionInTree(child, depth + 1, budget)
            if (found != null) return found
        }
        return null
    }

    // Reason for edit: Fast-path getSelectedText() whenever focused editable node has no active selection (including start==-1 on empty/unselected editors) and avoid double full-tree IPC walks per F-54, F-65, F-73, F-75 §2, and Build 39
    fun getSelectedText(): String? {
        val liveFocus = findFocusOverrideForTesting?.invoke(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (liveFocus != null) {
            val selected = extractSelectionFromNode(liveFocus, updateRemembered = true)
            if (!selected.isNullOrEmpty()) return selected
            if (isEditableNode(liveFocus) && liveFocus.isFocused) {
                val start = liveFocus.textSelectionStart
                val end = liveFocus.textSelectionEnd
                if ((start >= 0 && end == start) || (liveFocus.text != null && (start < 0 || end <= start))) {
                    // Focused editable node has no active selection; skip cached and full-tree IPC walks
                    return null
                }
            }
        }

        val cachedCandidates = listOf(
            validateCachedNode(lastSelectionNode),
            validateCachedNode(focusedNode),
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        )
        for (candidate in cachedCandidates) {
            val selected = extractSelectionFromNode(candidate, updateRemembered = true)
            if (!selected.isNullOrEmpty()) return selected
        }

        // Only walk the window tree (bounded) if no focused editable node was resolved above
        val root = rootInActiveWindow ?: return null
        val treeNode = findEditableWithSelectionInTree(root)
        return extractSelectionFromNode(treeNode, updateRemembered = true)
    }

    // Reason for edit: Support skipLiveQuery so callers that already ran getSelectedText() never run duplicate IPC walks, and avoid redundant refresh() calls (Build 39)
    fun getSelectedTextOrRemembered(skipLiveQuery: Boolean = false): String? {
        if (!skipLiveQuery) {
            val live = getSelectedText()
            if (!live.isNullOrBlank()) return live
        }

        val rem = rememberedHighlight ?: return null
        if (rem.text.isBlank()) return null

        // Guard 1: Must still be in the same app package
        if (rem.packageName != null && activeWindowPackage != null && rem.packageName != activeWindowPackage) {
            rememberedHighlight = null
            return null
        }

        // Guard 2: Verify the target node (or current active editable in the same window) is still editable and still contains the highlighted text
        val validRemNode = validateCachedNode(rem.node)
        val checkNode = validRemNode
            ?: resolveTargetNodeForPaste(preferSelectionNode = true)
            ?: rem.node
        if (checkNode != null) {
            if (checkNode !== validRemNode && checkNode === rem.node) {
                try {
                    checkNode.refresh()
                } catch (_: Exception) {}
            }
            if (isSensitiveNode(checkNode) || !isEditableNode(checkNode)) {
                rememberedHighlight = null
                return null
            }
            val currentText = checkNode.text?.toString()
            if (currentText != null && !currentText.contains(rem.text)) {
                rememberedHighlight = null
                return null
            }
        }
        return rem.text
    }

    fun clearRememberedHighlight() {
        rememberedHighlight = null
    }

    /**
     * Checks whether the currently highlighted selection exposes rich-text ParcelableSpans
     * (e.g. StyleSpan for bold/italic, UnderlineSpan, URLSpan, BulletSpan) or lives inside a WebView rich editor per F-65.
     */
    fun hasRichFormattingInSelection(): Boolean {
        val targetNode = resolveTargetNodeForPaste(preferSelectionNode = true) ?: return false

        try {
            targetNode.refresh()
        } catch (_: Exception) {}

        val rawText = targetNode.text ?: return false
        val selStart = targetNode.textSelectionStart
        val selEnd = targetNode.textSelectionEnd
        if (selStart < 0 || selEnd <= selStart || selEnd > rawText.length) return false

        if (rawText is android.text.Spanned) {
            val spans = rawText.getSpans(selStart, selEnd, Any::class.java) ?: emptyArray()
            val hasStyleSpan = spans.any { span ->
                span is android.text.style.StyleSpan ||
                    span is android.text.style.UnderlineSpan ||
                    span is android.text.style.StrikethroughSpan ||
                    span is android.text.style.URLSpan ||
                    span is android.text.style.BulletSpan ||
                    span is android.text.style.ForegroundColorSpan ||
                    span is android.text.style.BackgroundColorSpan ||
                    span is android.text.style.RelativeSizeSpan ||
                    span is android.text.style.TypefaceSpan
            }
            if (hasStyleSpan) return true
        }
        return false
    }

    // Reason for edit: Add replaceOrInsertText() to handle replacement and insertion for F-54 typed instructions and F-65 voice selection edits
    fun replaceOrInsertText(text: String, replaceSelection: Boolean, expectedSelection: String? = null) {
        if (BuildConfig.DEBUG) {
            Log.d("DictationService", "replaceOrInsertText called: replaceSelection=$replaceSelection text=$text")
        }
        // Backup to clipboard
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Whisper Unchained", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e("DictationService", "Clipboard copy failed", e)
        }

        val rem = rememberedHighlight
        val targetNode = resolveTargetNodeForPaste(preferSelectionNode = true)
            ?: (if (rem != null && (rem.packageName == null || activeWindowPackage == null || rem.packageName == activeWindowPackage)) validateCachedNode(rem.node) else null)

        if (targetNode != null && isSensitiveNode(targetNode)) {
            rememberedHighlight = null
            FloatingButtonService.getInstance()?.showTemporaryHint("Password field protected: copied to clipboard")
            return
        }

        if (targetNode != null && isEditableNode(targetNode)) {
            try {
                targetNode.refresh()
            } catch (_: Exception) {}

            val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                targetNode.isShowingHintText
            } else false
            val existingText = if (isShowingHint) "" else (targetNode.text?.toString() ?: "")
            var selStart = targetNode.textSelectionStart
            var selEnd = targetNode.textSelectionEnd

            // Reason for edit: If selection collapsed when touching the button or waiting for the server, restore from rememberedHighlight exact range first, then indexOf(expectedSelection) per F-73
            if (replaceSelection && (selStart < 0 || selEnd <= selStart) && !expectedSelection.isNullOrEmpty()) {
                if (rem != null && rem.selStart >= 0 && rem.selEnd > rem.selStart && rem.selEnd <= existingText.length &&
                    existingText.substring(rem.selStart, rem.selEnd) == expectedSelection
                ) {
                    selStart = rem.selStart
                    selEnd = rem.selEnd
                } else {
                    val idx = existingText.indexOf(expectedSelection)
                    if (idx >= 0) {
                        selStart = idx
                        selEnd = idx + expectedSelection.length
                    }
                }
                if (selStart >= 0 && selEnd > selStart) {
                    val restoreArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selStart)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selEnd)
                    }
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, restoreArgs)
                }
            }
            rememberedHighlight = null

            if (replaceSelection && selStart >= 0 && selEnd > selStart && selEnd <= existingText.length) {
                // Prefer ACTION_PASTE while selection is active so rich-text editors (e.g. Gmail) keep formatting outside the highlight
                val pasteSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasteSuccess) {
                    return
                }
                // Fallback: splice replacement via ACTION_SET_TEXT
                val newText = existingText.substring(0, selStart) + text + existingText.substring(selEnd)
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                val setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (setSuccess) {
                    val newCursor = selStart + text.length
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                    }
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    return
                }
            } else {
                // Not ticked or nothing selected: insert at cursor
                val insertPos = if (selEnd > selStart && selEnd <= existingText.length) selEnd else if (selStart >= 0) selStart else existingText.length
                val needsSpace = if (selEnd > selStart && insertPos > 0 && insertPos <= existingText.length) {
                    val prevChar = existingText[insertPos - 1]
                    !prevChar.isWhitespace() && (text.isNotEmpty() && !text.first().isWhitespace())
                } else false
                val toInsert = if (needsSpace) " $text" else text

                if (insertPos in 0..existingText.length) {
                    val newText = existingText.substring(0, insertPos) + toInsert + existingText.substring(insertPos)
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                    }
                    val setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (setSuccess) {
                        val newCursor = insertPos + toInsert.length
                        val selArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                        }
                        targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                        return
                    }
                }
            }
            // Fallback to pasteText
            pasteText(text)
        } else {
            pasteText(text)
        }
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