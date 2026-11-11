// This file provides the persistent floating microphone overlay and recording coordinator for Whisper Unchained.
// It is called by MainActivity or BootReceiver when starting the dictation service.
// Inputs: Audio captured from microphone or internal media projection, user touch interactions on the floating button.
// Outputs: Audio WAV files sent to ApiClient for transcription, resulting text routed to Accessibility Service or Clipboard.
// It manages floating button states (IDLE, RECORDING, PROCESSING, HIDDEN), soft pause recovery, Tailscale auto-recovery, and prompt-loop sanitization.

package com.example.whisperremote

// Reason for edit: Added soft pause handling (retaining audio, status 'paused' recovery polling without Tailscale resets) and updated name to Whisper Unchained per contract K-4.

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
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.widget.Toast
import com.example.whisperremote.data.InjectionMode
import com.example.whisperremote.data.ProviderPreset
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.net.TailscaleManager
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

    private enum class ButtonState {
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
    private var floatingButton: ImageView? = null
    private var hintLabel: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

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
                                pendingRetryAudioFile = null
                                stopPollingForResume()
                                if (retryFile != null && retryFile.exists()) {
                                    showTemporaryHint("PC resumed - transcribing...")
                                    processAudioFile(retryFile)
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

    private fun showTemporaryHint(textToShow: String) {
        mainHandler.post {
            fadeHintRunnable?.let { mainHandler.removeCallbacks(it) }
            hintLabel?.let { label ->
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
            hintLabel?.animate()?.cancel()
            hintLabel?.alpha = 1.0f
            hintLabel?.visibility = View.GONE
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, createNotification())
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    fun setMediaProjectionData(resultCode: Int, data: Intent): Boolean {
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

        floatingButton = ImageView(this).apply {
            setImageResource(R.drawable.whisper_remote)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        hintLabel = TextView(this).apply {
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
            addView(floatingButton, LinearLayout.LayoutParams(buttonPx, buttonPx))
            addView(hintLabel, LinearLayout.LayoutParams(
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

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        snapAnimator?.cancel()
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
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
                        if (!isMoved) {
                            handleTap()
                        } else {
                            snapToEdge()
                        }
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

    private fun handleTap() {
        when (state) {
            ButtonState.IDLE -> startRecording()
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
        }
    }

    fun onPasteComplete(hasFocus: Boolean) {
        mainHandler.post {
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
                container?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.IDLE -> {
                cancelTemporaryHint()
                if (wasHidden) bringToFront()
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.RECORDING -> {
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.RECORDING_NO_FOCUS -> {
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 0.4f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.PROCESSING -> {
                cancelTemporaryHint()
                container?.visibility = View.VISIBLE
                floatingButton?.alpha = 0.5f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        stopPollingForResume()
        pendingRetryAudioFile = null
        audioFile = File(externalCacheDir, "recording.wav")
        pauseActiveMedia()

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
            showTemporaryHint("Mic")
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

        val projectionAvailable = mediaProjection != null
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
        showTemporaryHint(actualLabel)
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
        audioFile?.let { file ->
            processAudioFile(file)
        } ?: run {
            setState(ButtonState.HIDDEN)
        }
    }

    private fun processAudioFile(file: File) {
        if (!file.exists() || file.length() <= 44) {
            setState(ButtonState.HIDDEN)
            return
        }

        setState(ButtonState.PROCESSING)

        apiClient.transcribeDetailed(file) { result ->
            when (result) {
                is TranscriptionResult.Success -> {
                    pendingRetryAudioFile = null
                    stopPollingForResume()
                    var text = result.text
                    // Reason for edit: Only sanitize hints on cloud presets; self-hosted server removes hint echo directly per contracts/01 §10
                    if (settingsRepository.providerPreset != ProviderPreset.SELF_HOSTED && settingsRepository.autoTrimHints) {
                        text = TranscriptSanitizer.sanitize(text, settingsRepository.promptHints)
                    }
                    Log.d("FloatingButtonService", "Transcribed (sanitized): $text")
                    mainHandler.post {
                        deliverTranscribedText(text)
                    }
                }
                is TranscriptionResult.Empty -> {
                    pendingRetryAudioFile = null
                    stopPollingForResume()
                    Log.d("FloatingButtonService", "Transcription returned empty/silence")
                    mainHandler.post {
                        showTemporaryHint("No speech detected")
                        setState(ButtonState.HIDDEN)
                    }
                }
                is TranscriptionResult.ServerPaused -> {
                    Log.i("FloatingButtonService", "Server paused: ${result.message}")
                    // Per contract K-4: treat 'paused' as REACHABLE (no Tailscale restart). Keep recording and start polling for resume.
                    pendingRetryAudioFile = file
                    mainHandler.post {
                        showTemporaryHint("Paused on your PC")
                        Toast.makeText(applicationContext, result.message.ifBlank { "Dictation is paused on your PC" }, Toast.LENGTH_LONG).show()
                        setState(ButtonState.IDLE)
                        startPollingForResume()
                    }
                }
                is TranscriptionResult.ModelUnavailable -> {
                    pendingRetryAudioFile = null
                    stopPollingForResume()
                    Log.w("FloatingButtonService", "Model loading; retry after ${result.retryAfterSeconds}s")
                    mainHandler.post {
                        showTemporaryHint("Model loading (${result.retryAfterSeconds}s)")
                        setState(ButtonState.HIDDEN)
                    }
                }
                is TranscriptionResult.AuthError -> {
                    pendingRetryAudioFile = null
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
                    stopPollingForResume()
                    Log.e("FloatingButtonService", "Network failure: ${result.error.message}")
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
                    stopPollingForResume()
                    Log.e("FloatingButtonService", "Server error ${result.code}: ${result.message}")
                    mainHandler.post {
                        val errorText = result.message.ifBlank { "Server error ${result.code}" }
                        showTemporaryHint("Error ${result.code}")
                        Toast.makeText(applicationContext, "PC rejected request (${result.code}): $errorText", Toast.LENGTH_LONG).show()
                        setState(ButtonState.HIDDEN)
                    }
                }
            }
        }
    }

    private fun deliverTranscribedText(text: String) {
        if (text.isBlank()) {
            setState(ButtonState.HIDDEN)
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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopPollingForResume()
        pendingRetryAudioFile = null
        snapAnimator?.cancel()
        snapAnimator = null

        try {
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error unregistering audio playback callback", e)
        }

        releaseAudioFocus()

        container?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error removing overlay", e)
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