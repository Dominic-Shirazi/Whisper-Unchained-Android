// This file provides the persistent floating microphone overlay and recording coordinator for Whisper Unchained.
// It is called by MainActivity or BootReceiver when starting the dictation service.
// Inputs: Audio captured from microphone or internal media projection, user touch interactions on the floating button and recording style pill.
// Outputs: Audio WAV files sent to ApiClient for transcription (with per-dictation style overrides), resulting text routed to Accessibility Service or Clipboard.
// It manages floating button states (IDLE, RECORDING, PROCESSING, HIDDEN), soft pause recovery, Tailscale auto-recovery, and prompt-loop sanitization.

package com.example.whisperremote

// Reason for edit: Add Typed instruction pill & panel, Trigger row in style sheet, remove Mean tone, and full opacity thinking button per F-54, F-56, F-58.
// Reason for edit: Add returnToRest() helper to restore IDLE state when text field remains focused on silent/empty recordings per F-47.
// Reason for edit: Never lose recordings on fallback failure, instant phone fallback on PC paused/model-loading, target-specific notices, and blank audio deletion per F-31.
// Reason for edit: Stored audio recordings in app-private cacheDir with immediate deletion after paste/failure and 24h stale purge per F-22.
// Reason for edit: Check RECORD_AUDIO before starting FGS, wrap startForeground in try/catch stopSelf(), and handle uninitialized managers per F-26.
// Reason for edit: Gated MediaProjection handling and audio playback callback with BuildConfig.FEATURE_INTERNAL_AUDIO per F-18.
// Reason for edit: Replaced stop button with large Done button and consumed scrim outside-taps via StyleSheetOverlayLayout per contract F-17.
// Reason for edit: Added missing setup check on floating button tap with actionable one-tap fix overlay per contract F-16.
// Reason for edit: Independent Mean tone and auto-promote to Fix grammar when selecting Professional/Casual/Friendly per contract F-15.
// Reason for edit: Exposed showTemporaryHint for accessibility clipboard fallback hints per contract F-12.
// Reason for edit: Added full-screen scrim (58% black fallback, animated) and experimental Samsung SemBlurInfo reflection per contract F-10.
// Reason for edit: Fallback dim 0.45 with 0.96 scale entrance for devices without cross-window blur, FLAG_NOT_FOCUSABLE to prevent keyboard dismissal/jitter, and BuildConfig.DEBUG-gated transcript logging per contract F-9.
// Reason for edit: Added recording style pill overlay with swipe gestures, haptic feedback, TalkBack actions, and per-dictation cleanup overrides per contract F-6.

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.example.whisperremote.data.CustomTrigger
import com.example.whisperremote.data.InjectionMode
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.net.TailscaleManager
import com.example.whisperremote.util.OnDeviceModelManager
import com.example.whisperremote.util.OnDeviceTranscriptionEngine
import com.example.whisperremote.util.PromptPanelLogic
import com.example.whisperremote.util.SamsungBlurHelper
import com.example.whisperremote.util.TranscriptSanitizer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatingButtonService : Service() {

    companion object {
        private var instance: FloatingButtonService? = null
        fun getInstance(): FloatingButtonService? = instance
    }

    internal enum class ButtonState {
        HIDDEN,               // no text field focused, not recording
        IDLE,                 // text field focused, ready to record
        RECORDING,            // recording, text field still focused
        RECORDING_NO_FOCUS,   // recording, user navigated away from text field
        PROCESSING            // transcribing / pasting
    }

    private enum class ActiveAudioSource {
        MIC,
        MEDIA_PLAYBACK
    }

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var container: LinearLayout? = null
    private var topLabel: TextView? = null
    private var floatingButton: ImageView? = null
    private var stylePillView: LinearLayout? = null
    private var pillContentContainer: LinearLayout? = null
    private var pillCategoryLabel: TextView? = null
    private var pillValueLabel: TextView? = null
    private var pillDotViews: Array<View>? = null
    private var bottomHintLabel: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // Reason for edit: Style pill & style sheet state per contracts F-6 & F-7
    private data class StyleDimension(
        val name: String,
        val values: List<String>,
        val labels: List<String>
    )

    private val dimensions = listOf(
        StyleDimension(
            name = "Grammar & flow",
            values = listOf("keep", "fix", "polish"),
            labels = listOf("Keep my words", "Fix grammar", "Polish")
        ),
        StyleDimension(
            name = "Conciseness",
            values = listOf("off", "light", "strong"),
            labels = listOf("Full length", "Shorter", "Shortest")
        ),
        StyleDimension(
            name = "Punctuation",
            values = listOf("minimal", "light", "standard"),
            labels = listOf("Minimal", "Light", "Standard")
        )
    )

    private var currentDimensionIndex = 0
    private var sessionRewrite = "keep"
    private var sessionShorten = "off"
    private var sessionPunctuation = "standard"
    private var sessionTone = "neutral"
    private var hasUserModifiedPill = false
    private var pendingRetryStyleOverrides: StyleOverrides? = null

    // Reason for edit: Pill auto-shrink state per contract F-8
    private var isPillShrunk = false
    private var pillShrinkRunnable: Runnable? = null

    // Style sheet overlay state
    private var sheetOverlayView: FrameLayout? = null
    private var isSheetOpen = false
    private var recordingStartTimeMs = 0L
    private var sheetTimerRunnable: Runnable? = null

    // Reason for edit: Missing setup prompt overlay per contract F-16
    private var setupPromptView: FrameLayout? = null

    private enum class MissingSetupStep {
        MICROPHONE,
        SERVER,
        ACCESSIBILITY
    }

    private var state = ButtonState.HIDDEN
    @Volatile
    private var isRecording = false
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var audioFile: File? = null
    private lateinit var apiClient: ApiClient
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var tailscaleManager: TailscaleManager

    // Reason for edit: On-device transcription engine and model manager per contract 05 §2.8
    private val onDeviceModelManager by lazy { OnDeviceModelManager(this) }
    private val onDeviceEngine by lazy { OnDeviceTranscriptionEngine(this, onDeviceModelManager, settingsRepository) }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentAudioSource = ActiveAudioSource.MIC
    private var didPauseAudioOnStart = false
    private var ignorePlaybackStateUntil = 0L

    private var mediaProjection: MediaProjection? = null

    private var snapAnimator: ValueAnimator? = null
    private val PREFS_NAME = "WhisperRemotePrefs"
    private val KEY_BUTTON_X = "floating_button_x"
    private val KEY_BUTTON_Y = "floating_button_y"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "FloatingButtonServiceChannel"
    private var fadeHintRunnable: Runnable? = null

    // Reason for edit: Variables and helpers for soft pause recovery per contract K-4
    private var pendingRetryAudioFile: File? = null
    private var pollResumeAttempts = 0
    private var pollResumeRunnable: Runnable? = null

    private fun stopPollingForResume() {
        pollResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        pollResumeRunnable = null
        pollResumeAttempts = 0
    }

    private fun startPollingForResume() {
        stopPollingForResume()
        val runnable = object : Runnable {
            override fun run() {
                val fileToRetry = pendingRetryAudioFile
                if (fileToRetry == null || !fileToRetry.exists()) {
                    stopPollingForResume()
                    return
                }
                if (pollResumeAttempts >= 60) { // Stop polling after 5 minutes of inactivity
                    stopPollingForResume()
                    return
                }
                pollResumeAttempts++
                apiClient.checkHealth { health ->
                    when (health) {
                        is HealthResult.Ok -> {
                            mainHandler.post {
                                val retryFile = pendingRetryAudioFile
                                val overrides = pendingRetryStyleOverrides
                                pendingRetryAudioFile = null
                                pendingRetryStyleOverrides = null
                                stopPollingForResume()
                                if (retryFile != null && retryFile.exists()) {
                                    showTemporaryHint("PC resumed - transcribing...")
                                    processAudioFile(retryFile, overrides)
                                }
                            }
                        }
                        is HealthResult.Paused -> {
                            // Still paused; continue polling quietly every 5s
                            mainHandler.postDelayed(this, 5000L)
                        }
                        else -> {
                            // Temporary network drop or model reload; continue polling quietly
                            mainHandler.postDelayed(this, 5000L)
                        }
                    }
                }
            }
        }
        pollResumeRunnable = runnable
        mainHandler.postDelayed(runnable, 5000L)
    }

    // Reason for edit: Exposed for clipboard fallback notifications per contract F-12
    fun showTemporaryHint(textToShow: String) {
        mainHandler.post {
            fadeHintRunnable?.let { mainHandler.removeCallbacks(it) }
            bottomHintLabel?.let { label ->
                label.animate().cancel()
                label.text = textToShow
                label.alpha = 1.0f
                label.visibility = View.VISIBLE

                val runnable = Runnable {
                    label.animate()
                        .alpha(0.0f)
                        .setDuration(500)
                        .withEndAction {
                            label.visibility = View.GONE
                        }
                        .start()
                }
                fadeHintRunnable = runnable
                mainHandler.postDelayed(runnable, 3000L)
            }
        }
    }

    private fun cancelTemporaryHint() {
        mainHandler.post {
            fadeHintRunnable?.let { mainHandler.removeCallbacks(it) }
            fadeHintRunnable = null
            bottomHintLabel?.animate()?.cancel()
            bottomHintLabel?.alpha = 1.0f
            bottomHintLabel?.visibility = View.GONE
        }
    }

    private fun isConfigPlaying(config: AudioPlaybackConfiguration): Boolean {
        return try {
            val method = config.javaClass.getMethod("getPlayerState")
            val state = method.invoke(config) as? Int
            state == 2 // 2 = PLAYER_STATE_STARTED
        } catch (e: Exception) {
            try {
                val method = config.javaClass.getMethod("isActive")
                (method.invoke(config) as? Boolean) == true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            super.onPlaybackConfigChanged(configs)

            if (System.currentTimeMillis() < ignorePlaybackStateUntil) {
                Log.d("FloatingButtonService", "Ignoring playback config change during start grace period")
                return
            }

            val isMediaPlaying = configs.any { config ->
                val playing = isConfigPlaying(config)
                val usage = config.audioAttributes.usage
                val isMediaUsage = usage == AudioAttributes.USAGE_MEDIA ||
                                   usage == AudioAttributes.USAGE_GAME ||
                                   usage == AudioAttributes.USAGE_UNKNOWN
                playing && isMediaUsage
            }

            Log.d("FloatingButtonService", "onPlaybackConfigChanged: activeMediaPlaying=$isMediaPlaying")
            onMediaPlaybackStateChanged(isMediaPlaying)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        apiClient = ApiClient(this)
        settingsRepository = SettingsRepository(this)
        tailscaleManager = TailscaleManager(this)
        cleanOldRecordings()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (BuildConfig.FEATURE_INTERNAL_AUDIO) {
            audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)
        }

        // Reason for edit: Pre-warm on-device recognizer off main thread per contract 05 §2.8
        Thread {
            try {
                if (settingsRepository.transcriptionTarget == TranscriptionTarget.ON_DEVICE.id || settingsRepository.fallbackToOnDevice) {
                    val modelId = settingsRepository.selectedOnDeviceModelId
                    if (onDeviceModelManager.isModelReady(modelId)) {
                        onDeviceEngine.initialize(modelId)
                    }
                }
            } catch (e: Exception) {
                Log.w("FloatingButtonService", "Error pre-warming on-device engine", e)
            }
        }.start()

        // Reason for edit: Verify RECORD_AUDIO permission before starting FGS to prevent SecurityException per F-26
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("FloatingButtonService", "RECORD_AUDIO permission not granted; stopping self.")
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "Microphone permission is required for the floating button.",
                    Toast.LENGTH_LONG
                ).show()
            }
            stopSelf()
            return
        }

        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to start foreground service: ${e.message}", e)
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "Cannot start service: microphone permission or foreground eligibility missing.",
                    Toast.LENGTH_LONG
                ).show()
            }
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun cleanOldRecordings() {
        try {
            val dir = cacheDir ?: return
            val now = System.currentTimeMillis()
            val maxAge = 24 * 60 * 60 * 1000L // 24 hours
            dir.listFiles { f -> f.name.startsWith("recording") && f.name.endsWith(".wav") }?.forEach { f ->
                if (now - f.lastModified() > maxAge) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error cleaning old recordings", e)
        }
    }

    private fun cleanupActiveAudioFile(keepIfPendingRetry: Boolean = false) {
        if (!keepIfPendingRetry || pendingRetryAudioFile != audioFile) {
            try {
                audioFile?.delete()
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error deleting active audio file", e)
            }
            audioFile = null
        }
    }

    fun setMediaProjectionData(resultCode: Int, data: Intent): Boolean {
        if (!BuildConfig.FEATURE_INTERNAL_AUDIO) {
            Log.w("FloatingButtonService", "Internal audio capture is not supported in this build")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            Log.d("FloatingButtonService", "MediaProjection created successfully!")
            true
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error creating MediaProjection", e)
            mediaProjection = null
            false
        }
    }

    private fun pauseActiveMedia() {
        try {
            didPauseAudioOnStart = audioManager.isMusicActive
            ignorePlaybackStateUntil = System.currentTimeMillis() + 1500L
            Log.d("FloatingButtonService", "pauseActiveMedia: wasMusicActive=$didPauseAudioOnStart")

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = focusRequest
            val result = audioManager.requestAudioFocus(focusRequest)
            Log.d("FloatingButtonService", "Audio focus request result: $result")

            if (didPauseAudioOnStart) {
                // Send KEYCODE_MEDIA_PAUSE event to ensure apps like TikTok/YouTube/YouTube Music pause immediately
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error pausing active media", e)
        }
    }

    private fun releaseAudioFocus() {
        try {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error releasing audio focus", e)
        }
        audioFocusRequest = null

        if (didPauseAudioOnStart) {
            didPauseAudioOnStart = false
            try {
                Log.d("FloatingButtonService", "Resuming audio playback that was paused on start")
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error dispatching media play event", e)
            }
        }
    }

    private fun onMediaPlaybackStateChanged(isPlaying: Boolean) {
        if (!BuildConfig.FEATURE_INTERNAL_AUDIO) return
        mainHandler.post {
            if (!isRecording) return@post

            val targetSource = if (isPlaying) ActiveAudioSource.MEDIA_PLAYBACK else ActiveAudioSource.MIC
            if (targetSource != currentAudioSource) {
                Log.d("FloatingButtonService", "Playback state changed: isPlaying=$isPlaying -> switching to $targetSource")
                switchAudioSource(targetSource)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val buttonPx = 150

        topLabel = TextView(this).apply {
            text = "Mic"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
        }

        floatingButton = ImageView(this).apply {
            setImageResource(R.drawable.whisper_remote)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        setupStylePill()
        setupTypePill()

        bottomHintLabel = TextView(this).apply {
            text = "Tap a text field to finish"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(topLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(4) })
            addView(floatingButton, LinearLayout.LayoutParams(buttonPx, buttonPx))
            addView(typePillView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(6) })
            addView(stylePillView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(6) })
            addView(bottomHintLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(4) })
            visibility = View.GONE
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val defaultMargin = dpToPx(8)
        val defaultX = screenWidth - buttonPx - defaultMargin
        val defaultY = dpToPx(120)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_BUTTON_X, defaultX)
        val savedY = prefs.getInt(KEY_BUTTON_Y, defaultY)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        windowParams = params

        container?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false
            private var longPressRunnable: Runnable? = null
            private var isLongPressFired = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        snapAnimator?.cancel()
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        isLongPressFired = false
                        expandPill()

                        // Reason for edit: Detect long press for button options menu per F-59
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        val runnable = Runnable {
                            if (!isMoved && state == ButtonState.IDLE) {
                                isLongPressFired = true
                                showButtonOptionsMenu()
                            }
                        }
                        longPressRunnable = runnable
                        mainHandler.postDelayed(runnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            expandPill()
                            params.x = initialX + dx
                            val screenHeight = resources.displayMetrics.heightPixels
                            val viewHeight = if (container?.height ?: 0 > 0) container!!.height else dpToPx(60)
                            val minY = dpToPx(24)
                            val maxY = screenHeight - viewHeight - dpToPx(48)
                            params.y = (initialY + dy).coerceIn(minY, maxY)

                            windowManager.updateViewLayout(container, params)
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        if (isLongPressFired) {
                            return true
                        }
                        if (!isMoved) {
                            handleTap()
                        } else {
                            snapToEdge()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        isLongPressFired = false
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to add overlay", e)
        }
    }

    private fun snapToEdge() {
        val containerView = container ?: return
        val params = windowParams ?: return

        snapAnimator?.cancel()

        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = if (containerView.width > 0) containerView.width else dpToPx(60)
        val margin = dpToPx(8)
        val currentCenterX = params.x + (viewWidth / 2)
        val targetX = if (currentCenterX < screenWidth / 2) {
            margin
        } else {
            screenWidth - viewWidth - margin
        }

        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                if (containerView.isAttachedToWindow) {
                    try {
                        windowManager.updateViewLayout(containerView, params)
                    } catch (e: Exception) {
                        Log.e("FloatingButtonService", "Error updating snapped view", e)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    savePosition(params.x, params.y)
                }
            })
            start()
        }
    }

    private fun savePosition(x: Int, y: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BUTTON_X, x).putInt(KEY_BUTTON_Y, y).apply()
    }

    // Reason for edit: Setup Typed instruction pill under floating button (idle only, self-hosted only) per contract 01 §12 and F-54
    private var typePillView: View? = null

    private fun setupTypePill() {
        val pill = TextView(this).apply {
            text = "Type"
            setTextColor(Color.parseColor("#E7E9EC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#EE1B1E25"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = bg
            setOnClickListener {
                openTypedInstructionPanel()
            }
            visibility = View.GONE
        }
        typePillView = pill
    }

    // Reason for edit: Setup Style Pill view under the floating button per contract F-6
    @SuppressLint("ClickableViewAccessibility")
    private fun setupStylePill() {
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(Color.parseColor("#EE1B1E25"))
            setStroke(dpToPx(1), Color.parseColor("#2B313C"))
        }

        pillCategoryLabel = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#868E9C"))
            gravity = Gravity.CENTER
            isAllCaps = false
        }

        pillValueLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#E7E9EC"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val dot0 = View(this)
        val dot1 = View(this)
        val dot2 = View(this)
        pillDotViews = arrayOf(dot0, dot1, dot2)

        val dotsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dot0)
            addView(dot1)
            addView(dot2)
        }

        pillContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pillCategoryLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(pillValueLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dpToPx(1)
                it.bottomMargin = dpToPx(3)
            })
            addView(dotsContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        stylePillView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = pillBg
            setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6))
            addView(pillContentContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            visibility = View.GONE
            isFocusable = true
        }

        // Accessibility Delegate with Custom Actions per F-6
        ViewCompat.setAccessibilityDelegate(stylePillView!!, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.contentDescription = getAccessibilityDescription()

                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_do_less,
                        "Do less"
                    )
                )
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_do_more,
                        "Do more"
                    )
                )
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_next_style,
                        "Next style"
                    )
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                return when (action) {
                    R.id.action_do_less -> {
                        stepStyleLess()
                        true
                    }
                    R.id.action_do_more -> {
                        stepStyleMore()
                        true
                    }
                    R.id.action_next_style -> {
                        cycleStyleNext()
                        true
                    }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        })

        // Touch Listener for 24dp Swipe Detection per F-6 & Tap per F-7
        stylePillView?.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var hasSwiped = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                expandPill()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        hasSwiped = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!hasSwiped) {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            val threshold = dpToPx(24)
                            if (Math.abs(dx) >= threshold || Math.abs(dy) >= threshold) {
                                hasSwiped = true
                                if (Math.abs(dx) >= Math.abs(dy)) {
                                    // Reason for edit: Carousel ViewPager physics per F-8:
                                    // Finger moves LEFT (dx < 0): row slides left, next option enters = "do more"
                                    // Finger moves RIGHT (dx > 0): row slides right, prev option enters = "do less"
                                    if (dx < 0) {
                                        stepStyleMore()
                                    } else {
                                        stepStyleLess()
                                    }
                                } else {
                                    // Vertical: UP / DOWN = cycle style dimension (wrapping)
                                    if (dy < 0) {
                                        cycleStyleNext()
                                    } else {
                                        cycleStylePrevious()
                                    }
                                }
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!hasSwiped) {
                            // Tap on pill opens style sheet per F-7
                            openStyleSheet()
                        }
                        hasSwiped = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        hasSwiped = false
                        return true
                    }
                }
                return false
            }
        })
    }

    // Reason for edit: Auto-shrink pill after 2s idle to 70% opacity & compact size per contract F-8
    private fun schedulePillShrink() {
        pillShrinkRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isRecording && (state == ButtonState.RECORDING || state == ButtonState.RECORDING_NO_FOCUS) && !isSheetOpen) {
                shrinkPill()
            }
        }
        pillShrinkRunnable = runnable
        mainHandler.postDelayed(runnable, 2000L)
    }

    private fun expandPill() {
        pillShrinkRunnable?.let { mainHandler.removeCallbacks(it) }
        if (isPillShrunk) {
            isPillShrunk = false
            stylePillView?.animate()
                ?.alpha(1.0f)
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.setDuration(150)
                ?.start()
            pillCategoryLabel?.visibility = View.VISIBLE
            stylePillView?.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6))
        }
        if (isRecording && !isSheetOpen) {
            schedulePillShrink()
        }
    }

    private fun shrinkPill() {
        if (!isPillShrunk && isRecording && !isSheetOpen) {
            isPillShrunk = true
            stylePillView?.animate()
                ?.alpha(0.7f)
                ?.scaleX(0.88f)
                ?.scaleY(0.88f)
                ?.setDuration(200)
                ?.start()
            pillCategoryLabel?.visibility = View.GONE
            stylePillView?.setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
        }
    }

    private fun getCurrentValue(): String {
        return when (currentDimensionIndex) {
            0 -> sessionRewrite
            1 -> sessionShorten
            2 -> sessionPunctuation
            3 -> sessionTone
            else -> ""
        }
    }

    private fun setCurrentValue(value: String) {
        hasUserModifiedPill = true
        expandPill()
        when (currentDimensionIndex) {
            0 -> sessionRewrite = value
            1 -> sessionShorten = value
            2 -> sessionPunctuation = value
            3 -> {
                // Reason for edit: Tone dimension per contract F-15 (mean mode moved to custom trigger per F-58)
                if (value == "neutral") {
                    sessionTone = "neutral"
                } else {
                    if (sessionRewrite == "keep") {
                        sessionRewrite = "fix"
                        showTemporaryHint("Switched to Fix grammar so the tone can change")
                        Toast.makeText(this@FloatingButtonService, "Switched to Fix grammar so the tone can change", Toast.LENGTH_SHORT).show()
                    }
                    sessionTone = value
                }
            }
        }
    }

    private fun getCurrentIndexInDimension(): Int {
        val dim = dimensions[currentDimensionIndex]
        val currVal = getCurrentValue()
        val idx = dim.values.indexOf(currVal)
        return if (idx >= 0) idx else 0
    }

    private fun stepStyleLess() {
        val dim = dimensions[currentDimensionIndex]
        val currentIndex = getCurrentIndexInDimension()
        if (currentIndex > 0) {
            val newIndex = currentIndex - 1
            setCurrentValue(dim.values[newIndex])
            triggerHapticTick()
            animateValueChange(fingerMovedLeft = false)
        } else {
            triggerLimitFeedback(fingerMovedLeft = false)
        }
    }

    private fun stepStyleMore() {
        val dim = dimensions[currentDimensionIndex]
        val currentIndex = getCurrentIndexInDimension()
        if (currentIndex < dim.values.size - 1) {
            val newIndex = currentIndex + 1
            setCurrentValue(dim.values[newIndex])
            triggerHapticTick()
            animateValueChange(fingerMovedLeft = true)
        } else {
            triggerLimitFeedback(fingerMovedLeft = true)
        }
    }

    private fun cycleStyleNext() {
        val newIndex = (currentDimensionIndex + 1) % dimensions.size
        animateDimensionChange(newIndex, isUp = true)
    }

    private fun cycleStylePrevious() {
        val newIndex = if (currentDimensionIndex - 1 < 0) dimensions.size - 1 else currentDimensionIndex - 1
        animateDimensionChange(newIndex, isUp = false)
    }

    private fun updateDots(activeIndex: Int) {
        pillDotViews?.forEachIndexed { index, dotView ->
            val isActive = index == activeIndex
            val size = if (isActive) dpToPx(7) else dpToPx(5)
            val color = if (isActive) Color.parseColor("#2FB79A") else Color.parseColor("#4B5363")
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            dotView.background = dotBg
            val lp = dotView.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(size, size)
            lp.width = size
            lp.height = size
            lp.setMargins(dpToPx(2), 0, dpToPx(2), 0)
            dotView.layoutParams = lp
        }
    }

    private fun updatePillContent(animated: Boolean = false) {
        val dim = dimensions[currentDimensionIndex]
        val valueIndex = getCurrentIndexInDimension()
        pillCategoryLabel?.text = dim.name
        pillValueLabel?.text = dim.labels[valueIndex]
        updateDots(valueIndex)
        stylePillView?.contentDescription = getAccessibilityDescription()
    }

    private fun announcePillChange() {
        val dim = dimensions[currentDimensionIndex]
        val valueIndex = getCurrentIndexInDimension()
        val text = "${dim.name}: ${dim.labels[valueIndex]}"
        stylePillView?.announceForAccessibility(text)
    }

    private fun getAccessibilityDescription(): String {
        val dim = dimensions[currentDimensionIndex]
        val valueIndex = getCurrentIndexInDimension()
        return "Cleanup style: ${dim.name}, ${dim.labels[valueIndex]}. Swipe left to do more, right to do less, up or down to switch style, or tap for full options."
    }

    private fun triggerHapticTick() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (e: Exception) {
            container?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun animateDimensionChange(newDimensionIndex: Int, isUp: Boolean) {
        val content = pillContentContainer ?: return
        expandPill()
        triggerHapticTick()
        val slideY = dpToPx(8).toFloat()
        val exitY = if (isUp) -slideY else slideY
        val enterY = if (isUp) slideY else -slideY

        content.animate()
            .alpha(0f)
            .translationY(exitY)
            .setDuration(85)
            .withEndAction {
                currentDimensionIndex = newDimensionIndex
                updatePillContent(animated = false)
                content.translationY = enterY
                content.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(95)
                    .withEndAction {
                        announcePillChange()
                    }
                    .start()
            }
            .start()
    }

    // Reason for edit: Carousel label animation per F-8 (outgoing leaves with finger, incoming enters from opposite side)
    private fun animateValueChange(fingerMovedLeft: Boolean) {
        val content = pillContentContainer ?: return
        expandPill()
        val slideDistance = dpToPx(16).toFloat()
        val exitX = if (fingerMovedLeft) -slideDistance else slideDistance
        val enterX = if (fingerMovedLeft) slideDistance else -slideDistance

        content.animate()
            .alpha(0f)
            .translationX(exitX)
            .setDuration(70)
            .withEndAction {
                updatePillContent(animated = false)
                content.translationX = enterX
                content.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(80)
                    .withEndAction {
                        announcePillChange()
                    }
                    .start()
            }
            .start()
    }

    private fun triggerLimitFeedback(fingerMovedLeft: Boolean) {
        val content = pillContentContainer ?: return
        expandPill()
        val bounceX = if (fingerMovedLeft) -dpToPx(4).toFloat() else dpToPx(4).toFloat()
        content.animate()
            .translationX(bounceX)
            .setDuration(40)
            .withEndAction {
                content.animate().translationX(0f).setDuration(40).start()
            }
            .start()
    }

    private fun ensureOverlayInBounds() {
        val containerView = container ?: return
        val params = windowParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        containerView.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
        )
        val width = containerView.measuredWidth
        val height = containerView.measuredHeight
        val margin = dpToPx(8)
        var changed = false
        if (params.x + width > screenWidth - margin) {
            params.x = (screenWidth - width - margin).coerceAtLeast(margin)
            changed = true
        }
        if (params.x < margin) {
            params.x = margin
            changed = true
        }
        if (params.y + height > screenHeight - dpToPx(48)) {
            params.y = (screenHeight - height - dpToPx(48)).coerceAtLeast(dpToPx(24))
            changed = true
        }
        if (changed && containerView.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(containerView, params)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error updating view layout in ensureOverlayInBounds", e)
            }
        }
    }

    // Reason for edit: Full style sheet overlay per contract F-7
    private var currentSheetCard: View? = null
    private var currentDimView: View? = null
    private var sheetTimerView: TextView? = null

    // Reason for edit: Custom triggers and per-session trigger selection per F-53, F-54, F-58
    private var cachedCustomTriggers: List<CustomTrigger> = emptyList()
    private var selectedSessionTrigger: String? = null
    private var typedOverlayView: View? = null
    private var typedInstructionDraft: String = ""
    private var buttonOptionsMenuOverlay: View? = null

    // Reason for edit: Floating button long-press options menu per F-59
    fun showButtonOptionsMenu() {
        if (buttonOptionsMenuOverlay != null) return
        triggerHapticTick()

        val screenWidth = resources.displayMetrics.widthPixels
        val overlay = FrameLayout(this)
        buttonOptionsMenuOverlay = overlay

        val dimView = View(this).apply {
            val hasSamsungBlur = SamsungBlurHelper.applySamsungWindowBlur(this)
            val scrimAlpha = if (hasSamsungBlur) 0.28f else 0.58f
            setBackgroundColor(Color.argb((255 * scrimAlpha).toInt(), 0, 0, 0))
            setOnClickListener {
                closeButtonOptionsMenu()
            }
        }
        overlay.addView(dimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#1B1E25"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            setOnClickListener { /* consume */ }
        }

        val cardWidth = minOf(screenWidth - dpToPx(48), dpToPx(320))
        val cardLp = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        overlay.addView(menuCard, cardLp)

        fun createMenuItem(title: String, subtitle: String? = null, onClick: () -> Unit): View {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                background = bg
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onClick()
                }
            }
            val titleView = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#E7E9EC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
            }
            itemLayout.addView(titleView)
            if (!subtitle.isNullOrBlank()) {
                val subView = TextView(this).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#868E9C"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                itemLayout.addView(subView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(2) })
            }
            return itemLayout
        }

        // 1. Type an instruction (opens typed panel without selection checkbox per F-59)
        menuCard.addView(createMenuItem(
            title = "Type an instruction",
            subtitle = "Send a written command to the AI"
        ) {
            closeButtonOptionsMenu()
            openTypedInstructionPanel(forceNoSelection = true)
        })

        val divider1 = View(this).apply {
            setBackgroundColor(Color.parseColor("#2B313C"))
        }
        menuCard.addView(divider1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(1)
        ).also {
            it.topMargin = dpToPx(4)
            it.bottomMargin = dpToPx(4)
        })

        // 2. Hide button
        menuCard.addView(createMenuItem(
            title = "Hide button",
            subtitle = "Hide until a text field is tapped"
        ) {
            closeButtonOptionsMenu()
            setState(ButtonState.HIDDEN)
        })

        val divider2 = View(this).apply {
            setBackgroundColor(Color.parseColor("#2B313C"))
        }
        menuCard.addView(divider2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(1)
        ).also {
            it.topMargin = dpToPx(4)
            it.bottomMargin = dpToPx(4)
        })

        // 3. Settings
        menuCard.addView(createMenuItem(
            title = "Settings",
            subtitle = "Open Whisper Unchained app"
        ) {
            closeButtonOptionsMenu()
            val intent = Intent(this@FloatingButtonService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        })

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(overlay, overlayParams)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error showing options menu overlay", e)
        }
    }

    fun closeButtonOptionsMenu() {
        val overlay = buttonOptionsMenuOverlay ?: return
        buttonOptionsMenuOverlay = null
        try {
            windowManager.removeView(overlay)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error removing options menu overlay", e)
        }
    }

    fun closeTypedInstructionPanel() {
        val overlay = typedOverlayView ?: return
        typedOverlayView = null
        try {
            windowManager.removeView(overlay)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error removing typed instruction overlay", e)
        }
    }

    fun openTypedInstructionPanel(forceNoSelection: Boolean = false) {
        if (typedOverlayView != null) return
        triggerHapticTick()

        // Fetch enabled triggers if not cached
        if (cachedCustomTriggers.isEmpty()) {
            apiClient.fetchCustomTriggers { res ->
                if (res is CustomTriggersResult.Success) {
                    cachedCustomTriggers = res.triggers.filter { it.enabled }
                }
            }
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val overlay = FrameLayout(this)
        typedOverlayView = overlay

        val dimView = View(this).apply {
            val hasSamsungBlur = SamsungBlurHelper.applySamsungWindowBlur(this)
            val scrimAlpha = if (hasSamsungBlur) 0.28f else 0.58f
            setBackgroundColor(Color.argb((255 * scrimAlpha).toInt(), 0, 0, 0))
            setOnClickListener {
                closeTypedInstructionPanel()
            }
        }
        overlay.addView(dimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#1B1E25"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            setOnClickListener { /* consume click */ }
        }

        val cardWidth = minOf(screenWidth - dpToPx(32), dpToPx(400))
        val cardLp = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        overlay.addView(card, cardLp)

        val selectedText = if (forceNoSelection) null else DictationAccessibilityService.getInstance()?.getSelectedText()
        val wordCount = selectedText?.trim()?.split("\\s+".toRegex())?.filter { it.isNotEmpty() }?.size ?: 0

        // 1. Suggestion container
        val suggestionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val sBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#222731"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = sBg
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            visibility = View.GONE
        }
        card.addView(suggestionsCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dpToPx(8) })

        // 2. Trigger matched line
        val triggerMatchedLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#8C9EFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
        }
        card.addView(triggerMatchedLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dpToPx(6) })

        // 3. One-time hint (only with selection)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hintDismissed = prefs.getBoolean("typed_selection_hint_dismissed", false)
        if (!selectedText.isNullOrBlank() && !hintDismissed) {
            val hintLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#242B38"))
                }
                background = hBg
                setPadding(dpToPx(10), dpToPx(6), dpToPx(8), dpToPx(6))
                val hText = TextView(this@FloatingButtonService).apply {
                    text = "Ticked: the AI changes the highlighted text. Not ticked: its answer goes in after it."
                    setTextColor(Color.parseColor("#C4CAD4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                addView(hText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))
                val closeBtn = TextView(this@FloatingButtonService).apply {
                    text = "✕"
                    setTextColor(Color.parseColor("#868E9C"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dpToPx(6), dpToPx(2), dpToPx(4), dpToPx(2))
                    setOnClickListener {
                        this@apply.visibility = View.GONE
                        prefs.edit().putBoolean("typed_selection_hint_dismissed", true).apply()
                    }
                }
                addView(closeBtn)
            }
            card.addView(hintLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(8) })
        }

        // 4. Text box row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val iBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#15171C"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = iBg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(8), dpToPx(8))
        }

        val editText = android.widget.EditText(this).apply {
            hint = "Type an instruction, or / for your triggers"
            setHintTextColor(Color.parseColor("#868E9C"))
            setTextColor(Color.parseColor("#E7E9EC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 8
            minLines = 1
            if (typedInstructionDraft.isNotEmpty()) {
                setText(typedInstructionDraft)
                setSelection(typedInstructionDraft.length)
            }
        }
        inputRow.addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))

        val sendButton = TextView(this).apply {
            text = "↑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val sBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8C9EFF"))
            }
            background = sBg
            setTextColor(Color.parseColor("#11142A"))
            alpha = if (editText.text.isNullOrBlank()) 0.4f else 1.0f
            isEnabled = !editText.text.isNullOrBlank()
        }
        val sLp = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).also { it.marginStart = dpToPx(6) }
        inputRow.addView(sendButton, sLp)
        card.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 5. Checkbox (only when selection present)
        var includeCheckBox: android.widget.CheckBox? = null
        if (!selectedText.isNullOrBlank()) {
            includeCheckBox = android.widget.CheckBox(this).apply {
                val label = PromptPanelLogic.includeLabel(wordCount)
                text = label
                setTextColor(Color.parseColor("#E7E9EC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isChecked = true
            }
            card.addView(includeCheckBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(6) })
        }

        fun updateSuggestions(text: String) {
            val whole = PromptPanelLogic.chosen(text, cachedCustomTriggers)
            if (whole != null) {
                triggerMatchedLabel.text = "Trigger: ${whole.name}"
                triggerMatchedLabel.visibility = View.VISIBLE
                suggestionsCard.removeAllViews()
                suggestionsCard.visibility = View.GONE
                return
            } else {
                triggerMatchedLabel.visibility = View.GONE
            }

            val sugs = PromptPanelLogic.suggest(text, cachedCustomTriggers)
            if (sugs.isEmpty()) {
                suggestionsCard.removeAllViews()
                suggestionsCard.visibility = View.GONE
            } else {
                suggestionsCard.removeAllViews()
                sugs.forEachIndexed { idx, tr ->
                    val row = LinearLayout(this@FloatingButtonService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                        val nameTv = TextView(this@FloatingButtonService).apply {
                            setText(tr.name)
                            setTextColor(Color.parseColor("#E7E9EC"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setTypeface(typeface, Typeface.BOLD)
                        }
                        addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))
                        val phraseTv = TextView(this@FloatingButtonService).apply {
                            setText(tr.phrases.firstOrNull() ?: "")
                            setTextColor(Color.parseColor("#868E9C"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        }
                        addView(phraseTv)
                        setOnClickListener {
                            val comp = PromptPanelLogic.completion(editText.text.toString(), tr)
                            editText.setText(comp)
                            editText.setSelection(comp.length)
                        }
                    }
                    suggestionsCard.addView(row)
                    if (idx < sugs.size - 1) {
                        val div = View(this@FloatingButtonService).apply {
                            setBackgroundColor(Color.parseColor("#2B313C"))
                        }
                        suggestionsCard.addView(div, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(1)
                        ))
                    }
                }
                suggestionsCard.visibility = View.VISIBLE
            }
        }

        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val str = s?.toString() ?: ""
                typedInstructionDraft = str
                val hasText = str.isNotBlank()
                sendButton.alpha = if (hasText) 1.0f else 0.4f
                sendButton.isEnabled = hasText
                updateSuggestions(str)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        fun doSend() {
            val instruction = editText.text.toString().trim()
            if (instruction.isEmpty()) return
            typedInstructionDraft = instruction
            val isInclude = includeCheckBox?.isChecked == true
            val sel = if (isInclude) selectedText else null

            closeTypedInstructionPanel()
            setState(ButtonState.PROCESSING)

            apiClient.editText(instruction, sel) { res ->
                when (res) {
                    is EditTextResult.Success -> {
                        if (res.edited) {
                            typedInstructionDraft = ""
                            DictationAccessibilityService.getInstance()?.replaceOrInsertText(
                                res.text,
                                replaceSelection = isInclude && !selectedText.isNullOrBlank()
                            )
                            setState(if (DictationAccessibilityService.hasFocusedEditable()) ButtonState.IDLE else ButtonState.HIDDEN)
                        } else {
                            setState(if (DictationAccessibilityService.hasFocusedEditable()) ButtonState.IDLE else ButtonState.HIDDEN)
                            showTemporaryHint("The AI editor didn't answer. Your instruction is still in the box.")
                        }
                    }
                    is EditTextResult.Unsupported -> {
                        setState(if (DictationAccessibilityService.hasFocusedEditable()) ButtonState.IDLE else ButtonState.HIDDEN)
                        showTemporaryHint("Update the PC app to type instructions")
                    }
                    is EditTextResult.ServerPaused -> {
                        setState(if (DictationAccessibilityService.hasFocusedEditable()) ButtonState.IDLE else ButtonState.HIDDEN)
                        showTemporaryHint("Dictation is paused on your PC")
                    }
                    is EditTextResult.Error -> {
                        setState(if (DictationAccessibilityService.hasFocusedEditable()) ButtonState.IDLE else ButtonState.HIDDEN)
                        if (res.code == 404 || res.code == 405) {
                            showTemporaryHint("Update the PC app to type instructions")
                        } else {
                            showTemporaryHint("The AI editor didn't answer. Your instruction is still in the box.")
                        }
                    }
                    is EditTextResult.NetworkError -> {
                        setState(if (DictationAccessibilityService.hasFocusedEditable()) ButtonState.IDLE else ButtonState.HIDDEN)
                        showTemporaryHint("The AI editor didn't answer. Your instruction is still in the box.")
                    }
                }
            }
        }

        sendButton.setOnClickListener { doSend() }
        editText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed)) {
                doSend()
                true
            } else {
                false
            }
        }

        // Window params with focus for IME
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.45f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }

        try {
            windowManager.addView(overlay, panelParams)
            editText.requestFocus()
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error showing typed instruction panel", e)
        }
    }

    // Reason for edit: Intercept and fully consume all outside touches so they never reach the underlying app or text field per contract F-17
    private class StyleSheetOverlayLayout(context: Context) : FrameLayout(context) {
        var onOutsideTap: (() -> Unit)? = null
        var cardView: View? = null

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val card = cardView
            if (card != null && card.isShown) {
                val loc = IntArray(2)
                card.getLocationOnScreen(loc)
                val left = loc[0]
                val top = loc[1]
                val right = left + card.width
                val bottom = top + card.height
                if (ev.rawX >= left && ev.rawX <= right && ev.rawY >= top && ev.rawY <= bottom) {
                    return super.dispatchTouchEvent(ev)
                }
            }
            if (ev.action == MotionEvent.ACTION_UP) {
                onOutsideTap?.invoke()
            }
            return true // Fully consumed outside card: protects underlying text box and cursor per contract F-17
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openStyleSheet() {
        if (isSheetOpen || (state != ButtonState.RECORDING && state != ButtonState.RECORDING_NO_FOCUS)) {
            return
        }
        isSheetOpen = true
        triggerHapticTick()

        val screenWidth = resources.displayMetrics.widthPixels

        val overlay = StyleSheetOverlayLayout(this).apply {
            this.onOutsideTap = {
                closeStyleSheet()
            }
        }
        sheetOverlayView = overlay

        val dimView = View(this)
        currentDimView = dimView
        overlay.addView(dimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Reason for edit: Try Samsung hardware window blur; if successful use 28% scrim, otherwise use 58% black scrim fallback per contract F-10
        val hasSamsungBlur = SamsungBlurHelper.applySamsungWindowBlur(dimView)
        val scrimAlpha = if (hasSamsungBlur) 0.28f else 0.58f
        val scrimColor = Color.argb((255 * scrimAlpha).toInt(), 0, 0, 0)
        dimView.setBackgroundColor(scrimColor)

        if (BuildConfig.DEBUG) {
            val pathName = if (hasSamsungBlur) "Samsung SemBlurInfo (scrim 28%)" else "black scrim fallback 58%"
            Log.d("FloatingButtonService", "Sheet backdrop initialized using: $pathName")
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#1B1E25"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = bg
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18))
            setOnClickListener { /* consume click on card */ }
        }
        currentSheetCard = card

        val maxCardWidth = dpToPx(380)
        val cardWidth = minOf(screenWidth - dpToPx(32), maxCardWidth)
        val cardLp = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        overlay.addView(card, cardLp)
        overlay.cardView = card

        // Populate card contents
        if (cachedCustomTriggers.isEmpty() && settingsRepository.providerPreset == ProviderPreset.SELF_HOSTED) {
            apiClient.fetchCustomTriggers { res ->
                if (res is CustomTriggersResult.Success) {
                    cachedCustomTriggers = res.triggers.filter { it.enabled }
                    (currentSheetCard as? LinearLayout)?.let { buildStyleSheetCard(it) }
                }
            }
        }
        buildStyleSheetCard(card)

        // Reason for edit: Use FLAG_NOT_FOCUSABLE + FLAG_ALT_FOCUSABLE_IM to place style sheet on top of IME keyboard without stealing focus
        val sheetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Reason for edit: Fallback dim 0.45 for devices without cross-window blur (e.g. Samsung One UI) per F-9
            dimAmount = 0.45f
        }

        // Reason for edit: Cross-window blur behind per contract F-8 item 3 / F-9
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (windowManager.isCrossWindowBlurEnabled) {
                    sheetParams.flags = sheetParams.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    sheetParams.blurBehindRadius = dpToPx(24)
                }
            } catch (e: Exception) {
                Log.w("FloatingButtonService", "FLAG_BLUR_BEHIND not supported on this device/ROM", e)
            }
        }

        try {
            windowManager.addView(overlay, sheetParams)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to add style sheet overlay", e)
            isSheetOpen = false
            return
        }

        // Live Timer runnable
        sheetTimerRunnable = object : Runnable {
            override fun run() {
                if (!isRecording || !isSheetOpen) return
                val elapsedSec = maxOf(0L, (System.currentTimeMillis() - recordingStartTimeMs) / 1000)
                val mins = elapsedSec / 60
                val secs = elapsedSec % 60
                sheetTimerView?.text = String.format("%02d:%02d", mins, secs)
                mainHandler.postDelayed(this, 500L)
            }
        }
        mainHandler.post(sheetTimerRunnable!!)

        // Reason for edit: Scale in from 0.96 with fade for clean, deliberate presentation without layout shift per F-9
        card.post {
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f

            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.alpha = 0f
            dimView.alpha = 0f

            dimView.animate().alpha(1f).setDuration(200).start()
            card.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun closeStyleSheet(onClosed: (() -> Unit)? = null) {
        if (!isSheetOpen) {
            onClosed?.invoke()
            return
        }
        isSheetOpen = false
        sheetTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sheetTimerRunnable = null

        val overlay = sheetOverlayView ?: run {
            onClosed?.invoke()
            return
        }
        val card = currentSheetCard
        val dim = currentDimView

        dim?.animate()?.alpha(0f)?.setDuration(160)?.start()
        // Reason for edit: Scale out to 0.96 with fade per F-9
        card?.animate()
            ?.scaleX(0.96f)
            ?.scaleY(0.96f)
            ?.alpha(0f)
            ?.setDuration(160)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction {
                try {
                    windowManager.removeView(overlay)
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Error removing sheet overlay", e)
                }
                sheetOverlayView = null
                currentSheetCard = null
                currentDimView = null
                sheetTimerView = null
                if (isRecording) {
                    expandPill()
                }
                onClosed?.invoke()
            }
            ?.start()
    }

    private fun buildStyleSheetCard(card: LinearLayout) {
        card.removeAllViews()

        // 1. Header with REC indicator and close button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val recDot = View(this).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D9694A"))
            }
            background = dotBg
        }
        header.addView(recDot, LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)))

        val recLabel = TextView(this).apply {
            text = "REC"
            setTextColor(Color.parseColor("#D9694A"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(recLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = dpToPx(6) })

        val timerView = TextView(this).apply {
            val elapsedSec = maxOf(0L, (System.currentTimeMillis() - recordingStartTimeMs) / 1000)
            val mins = elapsedSec / 60
            val secs = elapsedSec % 60
            text = String.format("%02d:%02d", mins, secs)
            setTextColor(Color.parseColor("#E7E9EC"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        sheetTimerView = timerView
        header.addView(timerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = dpToPx(8) })

        // Title in center / spacer
        val title = TextView(this).apply {
            text = "Dictation Style"
            setTextColor(Color.parseColor("#868E9C"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#868E9C"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener {
                closeStyleSheet()
            }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        card.addView(header)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2B313C"))
        }
        card.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(1)
        ).also {
            it.topMargin = dpToPx(10)
            it.bottomMargin = dpToPx(12)
        })

        // Scrollable content for the style rows to ensure all fit on any screen
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(rowsContainer)
        card.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Reason for edit: Add Trigger picker row for dictation style sheet per contract 01 §1 and F-54 §5
        val isSelfHosted = settingsRepository.providerPreset == ProviderPreset.SELF_HOSTED
        val hasTriggers = isSelfHosted && cachedCustomTriggers.isNotEmpty()
        if (hasTriggers) {
            val currentTriggerVal = selectedSessionTrigger
            val selectedTriggerObj = cachedCustomTriggers.firstOrNull { it.id == currentTriggerVal }
            val triggerLabel = selectedTriggerObj?.let { "${it.name} (${it.phrases.firstOrNull() ?: ""})" } ?: "None"

            val triggerContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleLabel = TextView(this).apply {
                text = "Trigger"
                setTextColor(Color.parseColor("#868E9C"))
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
            }
            triggerContainer.addView(titleLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dpToPx(8)
                it.bottomMargin = dpToPx(4)
            })

            val pickerCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor("#222731"))
                    setStroke(dpToPx(1), Color.parseColor("#2B313C"))
                }
                background = pBg
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                val nameTv = TextView(this@FloatingButtonService).apply {
                    text = triggerLabel
                    setTextColor(Color.parseColor("#E7E9EC"))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))
                val arrow = TextView(this@FloatingButtonService).apply {
                    text = "▼"
                    setTextColor(Color.parseColor("#868E9C"))
                    textSize = 10f
                }
                addView(arrow)

                setOnClickListener {
                    val items = arrayOf("None") + cachedCustomTriggers.map { "${it.name} (${it.phrases.firstOrNull() ?: ""})" }.toTypedArray()
                    android.app.AlertDialog.Builder(this@FloatingButtonService, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Select Trigger")
                        .setItems(items) { _, which ->
                            selectedSessionTrigger = if (which == 0) null else cachedCustomTriggers[which - 1].id
                            buildStyleSheetCard(card)
                        }
                        .show()
                }
            }
            triggerContainer.addView(pickerCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            if (selectedSessionTrigger != null) {
                val triggerNote = TextView(this).apply {
                    // Reason for edit: Clarify that picking None restores styles above matching Python style_pill
                    text = "The trigger decides how this one comes out. Choose None to use the styles above."
                    setTextColor(Color.parseColor("#8C9EFF"))
                    textSize = 11f
                }
                triggerContainer.addView(triggerNote, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(4) })
            }
            rowsContainer.addView(triggerContainer)
        }

        // When a trigger is chosen, grey out cleanup rows per F-54 §5
        val cleanupRowsEnabled = selectedSessionTrigger == null

        // Row 1: Grammar & flow
        buildOptionRow(
            container = rowsContainer,
            title = "Grammar & flow",
            values = listOf("keep", "fix", "polish"),
            labels = listOf("Keep my words", "Fix grammar", "Polish"),
            selectedValue = sessionRewrite,
            isEnabled = cleanupRowsEnabled,
            onSelect = { selected ->
                sessionRewrite = selected
                hasUserModifiedPill = true
                expandPill()
                triggerHapticTick()
                updatePillContent()
                buildStyleSheetCard(card)
            }
        )

        // Row 2: Conciseness (Full length | Shorter | Shortest per F-8)
        buildOptionRow(
            container = rowsContainer,
            title = "Conciseness",
            values = listOf("off", "light", "strong"),
            labels = listOf("Full length", "Shorter", "Shortest"),
            selectedValue = sessionShorten,
            isEnabled = cleanupRowsEnabled,
            onSelect = { selected ->
                sessionShorten = selected
                hasUserModifiedPill = true
                expandPill()
                triggerHapticTick()
                updatePillContent()
                buildStyleSheetCard(card)
            }
        )

        // Row 3: Punctuation
        buildOptionRow(
            container = rowsContainer,
            title = "Punctuation",
            values = listOf("minimal", "light", "standard"),
            labels = listOf("Minimal", "Light", "Standard"),
            selectedValue = sessionPunctuation,
            isEnabled = cleanupRowsEnabled,
            onSelect = { selected ->
                sessionPunctuation = selected
                hasUserModifiedPill = true
                expandPill()
                triggerHapticTick()
                updatePillContent()
                buildStyleSheetCard(card)
            }
        )

        // Row 4: Tone (Mean mode removed into custom trigger per F-58)
        val toneValues = listOf("neutral", "professional", "casual", "friendly")
        val toneLabels = listOf("My own tone", "Professional", "Casual", "Friendly")

        buildOptionRow(
            container = rowsContainer,
            title = "Tone",
            values = toneValues,
            labels = toneLabels,
            selectedValue = sessionTone,
            isEnabled = cleanupRowsEnabled,
            note = null,
            onSelect = { selected ->
                if (selected == "neutral") {
                    sessionTone = "neutral"
                } else {
                    if (sessionRewrite == "keep") {
                        sessionRewrite = "fix"
                        showTemporaryHint("Switched to Fix grammar so the tone can change")
                        Toast.makeText(this@FloatingButtonService, "Switched to Fix grammar so the tone can change", Toast.LENGTH_SHORT).show()
                    }
                    sessionTone = selected
                }
                hasUserModifiedPill = true
                expandPill()
                triggerHapticTick()
                updatePillContent()
                buildStyleSheetCard(card)
            }
        )

        // Large Done button (Contract F-17): closes sheet, recording continues
        val doneBtn = Button(this).apply {
            text = "Done"
            textSize = 15f
            setTextColor(Color.parseColor("#15171C"))
            setTypeface(typeface, Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#2FB79A"))
            }
            background = btnBg
            setOnClickListener {
                closeStyleSheet()
            }
        }
        card.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(46)
        ).also {
            it.topMargin = dpToPx(16)
        })
    }

    private fun buildOptionRow(
        container: LinearLayout,
        title: String,
        values: List<String>,
        labels: List<String>,
        selectedValue: String,
        isEnabled: Boolean,
        note: String? = null,
        onSelect: (String) -> Unit
    ) {
        val rowTitle = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#868E9C"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        container.addView(rowTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.topMargin = dpToPx(8)
            it.bottomMargin = dpToPx(4)
        })

        // Horizontal container for the option pills
        val pillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        values.forEachIndexed { index, value ->
            val label = labels[index]
            val isSelected = value == selectedValue
            val pill = TextView(this).apply {
                text = if (isSelected) "✓ $label" else label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                styleOptionPill(this, isSelected, isEnabled)
                contentDescription = if (isSelected) "$label, selected" else "$label, not selected"
                if (isEnabled) {
                    setOnClickListener {
                        onSelect(value)
                    }
                }
            }
            pillsRow.addView(pill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                if (index > 0) it.marginStart = dpToPx(6)
            })
        }

        // Wrap in horizontal scroll view in case pills exceed card width
        val horizontalScroll = android.widget.HorizontalScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(pillsRow)
        }
        container.addView(horizontalScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        if (note != null) {
            val noteView = TextView(this).apply {
                text = note
                setTextColor(Color.parseColor("#868E9C"))
                textSize = 10f
                setTypeface(typeface, Typeface.ITALIC)
            }
            container.addView(noteView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dpToPx(2)
            })
        }
    }

    private fun styleOptionPill(pillView: TextView, isSelected: Boolean, isEnabled: Boolean) {
        pillView.isEnabled = isEnabled
        pillView.alpha = if (isEnabled) 1.0f else 0.4f
        if (isSelected) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#2FB79A"))
            }
            pillView.background = bg
            pillView.setTextColor(Color.parseColor("#15171C"))
            pillView.setTypeface(pillView.typeface, Typeface.BOLD)
        } else {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#222731"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            pillView.background = bg
            pillView.setTextColor(Color.parseColor("#E7E9EC"))
            pillView.setTypeface(pillView.typeface, Typeface.NORMAL)
        }
    }

    // Reason for edit: Verify prerequisites before recording and surface actionable prompt per contract F-16
    private fun checkMissingSetup(): MissingSetupStep? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return MissingSetupStep.MICROPHONE
        }
        val isSelfHosted = settingsRepository.providerPreset == ProviderPreset.SELF_HOSTED
        val isCustom = settingsRepository.providerPreset == ProviderPreset.CUSTOM
        if ((isSelfHosted || isCustom) && (settingsRepository.baseUrl.isBlank() || settingsRepository.baseUrl.contains("example.com"))) {
            return MissingSetupStep.SERVER
        }
        if (!isSelfHosted && !isCustom && settingsRepository.apiKey.isBlank()) {
            return MissingSetupStep.SERVER
        }
        if (settingsRepository.injectionMode == InjectionMode.ACCESSIBILITY && !DictationAccessibilityService.isEnabled(this)) {
            return MissingSetupStep.ACCESSIBILITY
        }
        return null
    }

    private fun showSetupMissingPrompt(step: MissingSetupStep) {
        closeSetupMissingPrompt()
        triggerHapticTick()

        val rootOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            isClickable = true
            setOnClickListener { closeSetupMissingPrompt() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(Color.parseColor("#1B1E25"))
                setStroke(dpToPx(1), Color.parseColor("#2B313C"))
            }
            background = bg
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
            isClickable = true
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconView = TextView(this).apply {
            text = when (step) {
                MissingSetupStep.MICROPHONE -> "🎙️"
                MissingSetupStep.SERVER -> "🔗"
                MissingSetupStep.ACCESSIBILITY -> "✍️"
            }
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dpToPx(10) }
        }
        titleRow.addView(iconView)

        val titleView = TextView(this).apply {
            text = when (step) {
                MissingSetupStep.MICROPHONE -> "Microphone Access Needed"
                MissingSetupStep.SERVER -> "Whisper Server Not Set"
                MissingSetupStep.ACCESSIBILITY -> "Accessibility Is Off"
            }
            setTextColor(Color.parseColor("#E7E9EC"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleView)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#868E9C"))
            textSize = 18f
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener { closeSetupMissingPrompt() }
        }
        titleRow.addView(closeBtn)
        card.addView(titleRow)

        val descView = TextView(this).apply {
            text = when (step) {
                MissingSetupStep.MICROPHONE -> "Whisper Unchained needs microphone permission to capture your voice dictation."
                MissingSetupStep.SERVER -> "No Whisper server or cloud API key is configured. Scan your desktop pairing QR code or select a cloud provider."
                MissingSetupStep.ACCESSIBILITY -> "Accessibility is turned off, so Whisper Unchained cannot paste into text boxes automatically.\n\nTurn it on in Settings, or switch to Clipboard Mode to paste manually."
            }
            setTextColor(Color.parseColor("#868E9C"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dpToPx(10)
                it.bottomMargin = dpToPx(16)
            }
        }
        card.addView(descView)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        if (step == MissingSetupStep.ACCESSIBILITY) {
            val clipboardBtn = Button(this).apply {
                text = "Use Clipboard Mode"
                textSize = 12f
                setTextColor(Color.parseColor("#2FB79A"))
                val bgClip = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                background = bgClip
                setOnClickListener {
                    settingsRepository.injectionMode = InjectionMode.CLIPBOARD
                    closeSetupMissingPrompt()
                    showTemporaryHint("Switched to Clipboard Mode")
                    Toast.makeText(this@FloatingButtonService, "Switched to Clipboard Mode", Toast.LENGTH_SHORT).show()
                }
            }
            actionsRow.addView(clipboardBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40)
            ).also { it.marginEnd = dpToPx(8) })
        }

        val fixBtn = Button(this).apply {
            text = when (step) {
                MissingSetupStep.MICROPHONE -> "Allow Microphone"
                MissingSetupStep.SERVER -> "Open Setup"
                MissingSetupStep.ACCESSIBILITY -> "Open Settings"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#15171C"))
            setTypeface(typeface, Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#2FB79A"))
            }
            background = btnBg
            setOnClickListener {
                closeSetupMissingPrompt()
                when (step) {
                    MissingSetupStep.MICROPHONE, MissingSetupStep.SERVER -> {
                        val intent = Intent(this@FloatingButtonService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    }
                    MissingSetupStep.ACCESSIBILITY -> {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                }
            }
        }
        actionsRow.addView(fixBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(40)
        ))
        card.addView(actionsRow)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            setMargins(dpToPx(24), 0, dpToPx(24), 0)
        }
        rootOverlay.addView(card, cardParams)

        val promptParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        promptParams.gravity = Gravity.CENTER

        setupPromptView = rootOverlay
        try {
            windowManager.addView(rootOverlay, promptParams)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to show setup missing prompt", e)
        }
    }

    private fun closeSetupMissingPrompt() {
        setupPromptView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Failed to remove setup prompt", e)
            }
            setupPromptView = null
        }
    }

    private fun handleTap() {
        when (state) {
            ButtonState.IDLE -> {
                val missing = checkMissingSetup()
                if (missing != null) {
                    showSetupMissingPrompt(missing)
                    return
                }
                startRecording()
            }
            ButtonState.RECORDING -> stopRecording()
            ButtonState.RECORDING_NO_FOCUS -> { /* ignore — user needs to tap a text field first */ }
            ButtonState.PROCESSING -> { /* ignore — transcription in flight */ }
            ButtonState.HIDDEN -> { /* shouldn't happen */ }
        }
    }

    fun onTextFieldFocused() {
        mainHandler.post {
            when (state) {
                ButtonState.HIDDEN -> setState(ButtonState.IDLE)
                ButtonState.RECORDING_NO_FOCUS -> setState(ButtonState.RECORDING)
                ButtonState.IDLE -> updateTypePillVisibility()
                else -> {}
            }
        }
    }

    fun onTextFieldFocusLost() {
        mainHandler.post {
            when (state) {
                ButtonState.IDLE -> setState(ButtonState.HIDDEN)
                ButtonState.RECORDING -> setState(ButtonState.RECORDING_NO_FOCUS)
                else -> {}
            }
            updateTypePillVisibility()
        }
    }

    // Reason for edit: Follow text selection changes for Type pill visibility per F-59
    fun onTextSelectionChanged() {
        mainHandler.post {
            updateTypePillVisibility()
        }
    }

    fun updateTypePillVisibility() {
        val isSelfHosted = settingsRepository.providerPreset == ProviderPreset.SELF_HOSTED
        val hasSelection = try {
            val sel = DictationAccessibilityService.getInstance()?.getSelectedText()
            !sel.isNullOrBlank()
        } catch (_: Exception) { false }

        val shouldShow = state == ButtonState.IDLE && isSelfHosted && hasSelection
        typePillView?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    fun onPasteComplete(hasFocus: Boolean) {
        mainHandler.post {
            setState(if (hasFocus) ButtonState.IDLE else ButtonState.HIDDEN)
        }
    }

    // Reason for edit: Add returnToRest() helper to restore IDLE state when text field remains focused on silent/empty recordings per F-47
    fun returnToRest() {
        mainHandler.post {
            val a11y = DictationAccessibilityService.getInstance()
            val hasFocus = a11y?.hasFocusedEditable() == true
            setState(if (hasFocus) ButtonState.IDLE else ButtonState.HIDDEN)
        }
    }

    private fun bringToFront() {
        val view = container ?: return
        val params = windowParams ?: return
        try {
            windowManager.removeView(view)
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "bringToFront failed", e)
        }
    }

    private fun setState(newState: ButtonState) {
        val wasHidden = state == ButtonState.HIDDEN
        state = newState
        when (state) {
            ButtonState.HIDDEN -> {
                cancelTemporaryHint()
                closeStyleSheet()
                closeButtonOptionsMenu()
                closeTypedInstructionPanel()
                topLabel?.visibility = View.GONE
                stylePillView?.visibility = View.GONE
                typePillView?.visibility = View.GONE
                container?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.IDLE -> {
                closeStyleSheet()
                closeButtonOptionsMenu()
                topLabel?.visibility = View.GONE
                stylePillView?.visibility = View.GONE
                updateTypePillVisibility()
                if (wasHidden) bringToFront()
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.RECORDING -> {
                cancelTemporaryHint()
                closeButtonOptionsMenu()
                closeTypedInstructionPanel()
                typePillView?.visibility = View.GONE
                topLabel?.text = if (currentAudioSource == ActiveAudioSource.MEDIA_PLAYBACK) "Media" else "Mic"
                topLabel?.visibility = View.VISIBLE
                stylePillView?.visibility = View.VISIBLE
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.RECORDING_NO_FOCUS -> {
                closeButtonOptionsMenu()
                closeTypedInstructionPanel()
                typePillView?.visibility = View.GONE
                topLabel?.text = if (currentAudioSource == ActiveAudioSource.MEDIA_PLAYBACK) "Media" else "Mic"
                topLabel?.visibility = View.VISIBLE
                stylePillView?.visibility = View.VISIBLE
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 0.4f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.PROCESSING -> {
                cancelTemporaryHint()
                closeStyleSheet()
                closeButtonOptionsMenu()
                closeTypedInstructionPanel()
                topLabel?.visibility = View.GONE
                stylePillView?.visibility = View.GONE
                typePillView?.visibility = View.GONE
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote_thinking)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        stopPollingForResume()
        pendingRetryAudioFile = null
        pendingRetryStyleOverrides = null
        selectedSessionTrigger = null
        audioFile = File(cacheDir, "recording_${System.currentTimeMillis()}.wav")
        pauseActiveMedia()

        recordingStartTimeMs = System.currentTimeMillis()
        currentDimensionIndex = 0
        sessionRewrite = settingsRepository.cleanupRewrite
        sessionShorten = settingsRepository.cleanupShorten
        sessionPunctuation = settingsRepository.cleanupPunctuation
        sessionTone = settingsRepository.cleanupTone
        hasUserModifiedPill = false

        // Reason for edit: Full size at start per F-8 item 2, then auto-shrink after 2s
        isPillShrunk = false
        stylePillView?.alpha = 1.0f
        stylePillView?.scaleX = 1.0f
        stylePillView?.scaleY = 1.0f
        pillCategoryLabel?.visibility = View.VISIBLE
        stylePillView?.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6))

        updatePillContent(animated = false)
        ensureOverlayInBounds()
        schedulePillShrink()

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 2048)

        currentAudioSource = ActiveAudioSource.MIC
        audioRecord = createMicAudioRecord(sampleRate, channelConfig, audioFormat, bufferSize)

        try {
            audioRecord?.startRecording()
            isRecording = true
            setState(ButtonState.RECORDING)
            Log.d("FloatingButtonService", "Recording started (16kHz PCM MIC)")

            val targetFile = audioFile!!
            recordingThread = Thread {
                writeAudioDataToWavFile(targetFile, bufferSize)
            }.apply { start() }

        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Start recording failed", e)
            releaseAudioResources()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMicAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        val sessionAudioId = record.audioSessionId
        if (sessionAudioId != 0) {
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionAudioId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Failed to create NoiseSuppressor", e)
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionAudioId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Failed to create AcousticEchoCanceler", e)
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(sessionAudioId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Failed to create AutomaticGainControl", e)
                }
            }
        }
        return record
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecordForSource(
        source: ActiveAudioSource,
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord {
        if (source == ActiveAudioSource.MEDIA_PLAYBACK) {
            val projection = mediaProjection
            if (projection != null) {
                try {
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    return AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Failed building AudioPlaybackCapture, fallback to MIC", e)
                    mediaProjection = null
                }
            } else {
                Log.w("FloatingButtonService", "No MediaProjection available, fallback to MIC")
            }
        }
        return createMicAudioRecord(sampleRate, channelConfig, audioFormat, bufferSize)
    }

    private fun switchAudioSource(newSource: ActiveAudioSource) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 2048)

        releaseAudioResources()

        val projectionAvailable = BuildConfig.FEATURE_INTERNAL_AUDIO && mediaProjection != null
        val targetSource = if (newSource == ActiveAudioSource.MEDIA_PLAYBACK && projectionAvailable) {
            ActiveAudioSource.MEDIA_PLAYBACK
        } else {
            ActiveAudioSource.MIC
        }

        currentAudioSource = targetSource

        if (targetSource == ActiveAudioSource.MEDIA_PLAYBACK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        1,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Failed to update foreground service type to mediaProjection", e)
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        1,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Failed to update foreground service type to microphone", e)
                }
            }
        }

        audioRecord = createAudioRecordForSource(targetSource, sampleRate, channelConfig, audioFormat, bufferSize)

        try {
            audioRecord?.startRecording()
            Log.d("FloatingButtonService", "Switched audio source to $targetSource")
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to start AudioRecord after switch", e)
        }

        val actualLabel = if (targetSource == ActiveAudioSource.MEDIA_PLAYBACK) "Media" else "Mic"
        topLabel?.text = actualLabel
        topLabel?.visibility = View.VISIBLE
    }

    private fun writeAudioDataToWavFile(file: File, bufferSize: Int) {
        val data = ByteArray(bufferSize)
        val fos: FileOutputStream
        try {
            fos = FileOutputStream(file)
            fos.write(ByteArray(44))
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to create WAV file stream", e)
            return
        }

        var totalAudioLen = 0L
        try {
            while (isRecording) {
                val record = audioRecord
                if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(data, 0, data.size)
                    if (read > 0) {
                        fos.write(data, 0, read)
                        totalAudioLen += read
                    }
                } else {
                    Thread.sleep(20)
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error writing PCM data", e)
        } finally {
            try {
                fos.close()
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error closing FileOutputStream", e)
            }
        }

        writeWavHeader(file, totalAudioLen, 16000, 1, 16)
    }

    private fun writeWavHeader(
        file: File,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalAudioLen.toInt())

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error writing WAV header", e)
        }
    }

    private fun stopRecording() {
        isRecording = false
        pillShrinkRunnable?.let { mainHandler.removeCallbacks(it) }
        pillShrinkRunnable = null
        isPillShrunk = false
        closeStyleSheet()
        releaseAudioFocus()

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "AudioRecord stop failed", e)
        }

        recordingThread?.let {
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Log.e("FloatingButtonService", "Recording thread join interrupted", e)
            }
        }
        recordingThread = null

        releaseAudioResources()

        val overrides = if (hasUserModifiedPill) {
            StyleOverrides(
                rewrite = sessionRewrite,
                shorten = sessionShorten,
                punctuation = sessionPunctuation,
                tone = sessionTone
            )
        } else {
            null
        }

        audioFile?.let { file ->
            processAudioFile(file, overrides)
        } ?: run {
            returnToRest()
        }
    }

    private fun processAudioFile(file: File, styleOverrides: StyleOverrides? = null) {
        if (!file.exists() || file.length() <= 44) {
            showTemporaryHint("No speech detected")
            returnToRest()
            return
        }

        setState(ButtonState.PROCESSING)

        // Reason for edit: Direct on-device transcription when Target is ON_DEVICE per contract 05 §2.3
        if (settingsRepository.transcriptionTarget == TranscriptionTarget.ON_DEVICE.id) {
            processOnDeviceTranscription(file, fallbackNotice = false, styleOverrides = styleOverrides)
            return
        }

        val triggerToSend = selectedSessionTrigger
        selectedSessionTrigger = null

        apiClient.transcribeDetailed(file, styleOverrides, trigger = triggerToSend) { result ->
            when (result) {
                is TranscriptionResult.Success -> {
                    pendingRetryAudioFile = null
                    pendingRetryStyleOverrides = null
                    stopPollingForResume()
                    var text = result.text
                    // Reason for edit: Only sanitize hints on cloud presets; self-hosted server removes hint echo directly per contracts/01 §10
                    if (settingsRepository.providerPreset != ProviderPreset.SELF_HOSTED && settingsRepository.autoTrimHints) {
                        text = TranscriptSanitizer.sanitize(text, settingsRepository.promptHints)
                    }
                    // Reason for edit: Gate transcript logging behind BuildConfig.DEBUG to protect privacy in release builds per contract F-9
                    if (BuildConfig.DEBUG) {
                        Log.d("FloatingButtonService", "Transcribed (sanitized): $text")
                    } else {
                        Log.d("FloatingButtonService", "Transcribed (sanitized): ${text.length} chars")
                    }
                    mainHandler.post {
                        deliverTranscribedText(text)
                    }
                }
                is TranscriptionResult.Empty -> {
                    pendingRetryAudioFile = null
                    pendingRetryStyleOverrides = null
                    stopPollingForResume()
                    Log.d("FloatingButtonService", "Transcription returned empty/silence")
                    mainHandler.post {
                        showTemporaryHint("No speech detected")
                        returnToRest()
                    }
                }
                is TranscriptionResult.ServerPaused -> {
                    Log.i("FloatingButtonService", "Server paused: ${result.message}")
                    // Reason for edit: Fallback to on-device immediately when PC is paused per F-31
                    if (settingsRepository.fallbackToOnDevice && (onDeviceModelManager.isModelReady(settingsRepository.selectedOnDeviceModelId) || onDeviceModelManager.isModelReady(OnDeviceModelManager.DEFAULT_MODEL_ID))) {
                        Log.i("FloatingButtonService", "Falling back to on-device transcription: PC is paused")
                        processOnDeviceTranscription(file, fallbackNotice = true, customNotice = "Your PC is paused: transcribed on this phone", styleOverrides = styleOverrides)
                        return@transcribeDetailed
                    }

                    // Per contract K-4: treat 'paused' as REACHABLE (no Tailscale restart). Keep recording and start polling for resume.
                    pendingRetryAudioFile = file
                    pendingRetryStyleOverrides = styleOverrides
                    mainHandler.post {
                        showTemporaryHint("Paused on your PC")
                        Toast.makeText(applicationContext, result.message.ifBlank { "Dictation is paused on your PC" }, Toast.LENGTH_LONG).show()
                        setState(ButtonState.IDLE)
                        startPollingForResume()
                    }
                }
                is TranscriptionResult.ModelUnavailable -> {
                    Log.w("FloatingButtonService", "Model loading; retry after ${result.retryAfterSeconds}s")
                    // Reason for edit: Fallback to on-device immediately when PC model is loading per F-31
                    if (settingsRepository.fallbackToOnDevice && (onDeviceModelManager.isModelReady(settingsRepository.selectedOnDeviceModelId) || onDeviceModelManager.isModelReady(OnDeviceModelManager.DEFAULT_MODEL_ID))) {
                        Log.i("FloatingButtonService", "Falling back to on-device transcription: PC model loading")
                        processOnDeviceTranscription(file, fallbackNotice = true, customNotice = "PC model is loading: transcribed on this phone", styleOverrides = styleOverrides)
                        return@transcribeDetailed
                    }

                    pendingRetryAudioFile = null
                    pendingRetryStyleOverrides = null
                    stopPollingForResume()
                    mainHandler.post {
                        showTemporaryHint("Model loading (${result.retryAfterSeconds}s)")
                        setState(ButtonState.HIDDEN)
                    }
                }
                is TranscriptionResult.AuthError -> {
                    pendingRetryAudioFile = null
                    pendingRetryStyleOverrides = null
                    stopPollingForResume()
                    Log.e("FloatingButtonService", "API key rejected: ${result.message}")
                    mainHandler.post {
                        val msg = result.message.ifBlank { "API key rejected (401)" }
                        showTemporaryHint("API key rejected")
                        Toast.makeText(applicationContext, "PC rejected request: $msg", Toast.LENGTH_LONG).show()
                        setState(ButtonState.HIDDEN)
                    }
                }
                is TranscriptionResult.NetworkError -> {
                    pendingRetryAudioFile = null
                    pendingRetryStyleOverrides = null
                    stopPollingForResume()
                    Log.e("FloatingButtonService", "Network failure: ${result.error.message}")

                    // Reason for edit: Fallback to on-device transcription when PC/cloud is unreachable per contract 05 §2.3 & F-31
                    if (settingsRepository.fallbackToOnDevice) {
                        Log.i("FloatingButtonService", "Falling back to on-device transcription: Network failure")
                        processOnDeviceTranscription(file, fallbackNotice = true, styleOverrides = styleOverrides)
                        return@transcribeDetailed
                    }

                    if (settingsRepository.autoRestartTailscale && tailscaleManager.isTailscaleInstalled()) {
                        tailscaleManager.restartTailscaleIfAllowed()
                    }
                    mainHandler.post {
                        showTemporaryHint("Connection failed")
                        Toast.makeText(applicationContext, "Connection failed: ${result.error.localizedMessage ?: "Cannot reach server"}", Toast.LENGTH_LONG).show()
                        setState(ButtonState.HIDDEN)
                    }
                }
                is TranscriptionResult.ServerError -> {
                    pendingRetryAudioFile = null
                    pendingRetryStyleOverrides = null
                    stopPollingForResume()
                    Log.e("FloatingButtonService", "Server error ${result.code}: ${result.message}")

                    // Reason for edit: Fallback to on-device transcription on 5xx server errors per contract 05 §2.3 & F-31
                    if (result.code >= 500 && settingsRepository.fallbackToOnDevice) {
                        Log.i("FloatingButtonService", "Falling back to on-device transcription: Server error ${result.code}")
                        processOnDeviceTranscription(file, fallbackNotice = true, styleOverrides = styleOverrides)
                        return@transcribeDetailed
                    }

                    mainHandler.post {
                        val errorText = result.message.ifBlank { "Server error ${result.code}" }
                        showTemporaryHint("Error ${result.code}")
                        Toast.makeText(applicationContext, "PC rejected request (${result.code}): $errorText", Toast.LENGTH_LONG).show()
                        setState(ButtonState.HIDDEN)
                    }
                }
            }
            if (result !is TranscriptionResult.ServerPaused) {
                cleanupActiveAudioFile(keepIfPendingRetry = false)
            }
        }
    }

    // Reason for edit: Helper for executing on-device transcription off main thread with fallback retry preservation and target-specific notices per F-31
    private fun processOnDeviceTranscription(
        file: File,
        fallbackNotice: Boolean,
        customNotice: String? = null,
        styleOverrides: StyleOverrides? = null
    ) {
        Thread {
            try {
                var modelId = settingsRepository.selectedOnDeviceModelId
                if (!onDeviceModelManager.isModelReady(modelId)) {
                    modelId = OnDeviceModelManager.DEFAULT_MODEL_ID
                }
                if (!onDeviceEngine.isInitialized || onDeviceEngine.loadedModelId != modelId) {
                    val ok = onDeviceEngine.initialize(modelId)
                    if (!ok) {
                        handleOnDeviceFailure(file, isFallback = fallbackNotice, styleOverrides = styleOverrides, failureReason = "On-device model is not installed")
                        return@Thread
                    }
                }

                val text = onDeviceEngine.transcribe(file, removeFillers = settingsRepository.removeFillerSounds)
                mainHandler.post {
                    if (fallbackNotice) {
                        val notice = customNotice ?: if (settingsRepository.transcriptionTarget == TranscriptionTarget.CLOUD.id) {
                            "Transcribed on this phone: the cloud service couldn't be reached"
                        } else {
                            "Transcribed on this phone: your PC couldn't be reached"
                        }
                        Toast.makeText(applicationContext, notice, Toast.LENGTH_LONG).show()
                    }
                    if (text.isBlank()) {
                        // Per F-31 item 2: Delete recording immediately on blank speech detection
                        cleanupActiveAudioFile(keepIfPendingRetry = false)
                        showTemporaryHint("No speech detected")
                        returnToRest()
                    } else {
                        deliverTranscribedText(text)
                    }
                }
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "On-device transcription failed", e)
                handleOnDeviceFailure(file, isFallback = fallbackNotice, styleOverrides = styleOverrides, failureReason = "Transcription failed")
            }
        }.start()
    }

    // Reason for edit: Never lose recordings when phone fallback fails; preserve file as pendingRetryAudioFile per F-31 item 1
    private fun handleOnDeviceFailure(
        file: File,
        isFallback: Boolean,
        styleOverrides: StyleOverrides?,
        failureReason: String
    ) {
        mainHandler.post {
            pendingRetryAudioFile = file
            pendingRetryStyleOverrides = styleOverrides
            if (isFallback) {
                showTemporaryHint("Couldn't transcribe on the phone; will send when your PC is back")
                Toast.makeText(applicationContext, "Couldn't transcribe on the phone; will send when your PC is back", Toast.LENGTH_LONG).show()
                setState(ButtonState.IDLE)
                startPollingForResume()
            } else {
                showTemporaryHint(failureReason)
                Toast.makeText(applicationContext, failureReason, Toast.LENGTH_SHORT).show()
                setState(ButtonState.IDLE)
            }
        }
    }

    private fun deliverTranscribedText(text: String) {
        cleanupActiveAudioFile(keepIfPendingRetry = false)
        if (text.isBlank()) {
            showTemporaryHint("No speech detected")
            returnToRest()
            return
        }

        if (settingsRepository.injectionMode == InjectionMode.CLIPBOARD) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Whisper Unchained", text)
                clipboard.setPrimaryClip(clip)
                showTemporaryHint("Copied to clipboard")
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Clipboard copy error", e)
            }
            setState(ButtonState.HIDDEN)
        } else {
            val accessibilityService = DictationAccessibilityService.getInstance()
            if (accessibilityService != null) {
                accessibilityService.pasteText(text)
            } else {
                // Fallback to clipboard if accessibility service is not active
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Whisper Unchained", text)
                    clipboard.setPrimaryClip(clip)
                    showTemporaryHint("Copied (Accessibility off)")
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Fallback clipboard copy error", e)
                }
                setState(ButtonState.HIDDEN)
            }
        }
    }

    private fun releaseAudioResources() {
        try {
            noiseSuppressor?.release()
            echoCanceler?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error releasing audio effects", e)
        }
        noiseSuppressor = null
        echoCanceler = null
        automaticGainControl = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Whisper Unchained Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Unchained")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    @androidx.annotation.VisibleForTesting
    fun getPendingRetryAudioFile(): File? = pendingRetryAudioFile

    @androidx.annotation.VisibleForTesting
    fun setPendingRetryAudioFile(file: File?) {
        pendingRetryAudioFile = file
    }

    @androidx.annotation.VisibleForTesting
    fun triggerOnDeviceFailureForTesting(file: File, isFallback: Boolean, failureReason: String) {
        handleOnDeviceFailure(file, isFallback, null, failureReason)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopPollingForResume()
        closeStyleSheet()
        closeButtonOptionsMenu()
        closeTypedInstructionPanel()
        closeSetupMissingPrompt()
        // Reason for edit: Release on-device recognizer and VAD native resources per contract 05 §2.8
        onDeviceEngine.release()
        if (::windowManager.isInitialized) {
            sheetOverlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Error removing sheet overlay on destroy", e)
                }
                sheetOverlayView = null
            }
        }
        pendingRetryAudioFile = null
        pendingRetryStyleOverrides = null
        snapAnimator?.cancel()
        snapAnimator = null

        if (::audioManager.isInitialized) {
            try {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error unregistering audio playback callback", e)
            }

            releaseAudioFocus()
            if (BuildConfig.FEATURE_INTERNAL_AUDIO) {
                try {
                    audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Error unregistering audio playback callback", e)
                }
            }
        }

        if (::windowManager.isInitialized) {
            container?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e("FloatingButtonService", "Error removing overlay", e)
                }
            }
        }
        isRecording = false
        releaseAudioResources()

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error stopping MediaProjection", e)
        }
        mediaProjection = null
    }
}