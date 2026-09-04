package com.example.whisperremote

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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

    private lateinit var windowManager: WindowManager
    private var container: LinearLayout? = null
    private var floatingButton: ImageView? = null
    private var hintLabel: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var state = ButtonState.HIDDEN
    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var audioFile: File? = null
    private lateinit var apiClient: ApiClient

    private var snapAnimator: ValueAnimator? = null
    private val PREFS_NAME = "WhisperRemotePrefs"
    private val KEY_BUTTON_X = "floating_button_x"
    private val KEY_BUTTON_Y = "floating_button_y"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "FloatingButtonServiceChannel"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        apiClient = ApiClient(this)
        createNotificationChannel()
        startForeground(1, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val buttonPx = 150  // keep same size as before

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

    // Called by DictationAccessibilityService when an editable field gains focus
    fun onTextFieldFocused() {
        mainHandler.post {
            when (state) {
                ButtonState.HIDDEN -> setState(ButtonState.IDLE)
                ButtonState.RECORDING_NO_FOCUS -> setState(ButtonState.RECORDING)
                else -> {}
            }
        }
    }

    // Called by DictationAccessibilityService when focus leaves all editable fields
    fun onTextFieldFocusLost() {
        mainHandler.post {
            when (state) {
                ButtonState.IDLE -> setState(ButtonState.HIDDEN)
                ButtonState.RECORDING -> setState(ButtonState.RECORDING_NO_FOCUS)
                else -> { /* PROCESSING stays visible until paste completes */ }
            }
        }
    }

    // Called by DictationAccessibilityService after paste completes
    fun onPasteComplete(hasFocus: Boolean) {
        mainHandler.post {
            setState(if (hasFocus) ButtonState.IDLE else ButtonState.HIDDEN)
        }
    }

    // Re-add the overlay to WindowManager so it sits on top of other overlays
    // (e.g. chat bubbles). Same-type windows are z-ordered by insertion time.
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
                container?.visibility = View.GONE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.IDLE -> {
                if (wasHidden) bringToFront()
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.RECORDING -> {
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.RECORDING_NO_FOCUS -> {
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.VISIBLE
                floatingButton?.alpha = 0.4f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.PROCESSING -> {
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 0.5f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        audioFile = File(externalCacheDir, "recording.wav")
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            val sessionAudioId = audioRecord?.audioSessionId ?: 0
            if (sessionAudioId != 0) {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionAudioId)?.apply {
                        enabled = true
                        Log.d("FloatingButtonService", "Hardware NoiseSuppressor enabled")
                    }
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionAudioId)?.apply {
                        enabled = true
                        Log.d("FloatingButtonService", "Hardware AcousticEchoCanceler enabled")
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    automaticGainControl = AutomaticGainControl.create(sessionAudioId)?.apply {
                        enabled = true
                        Log.d("FloatingButtonService", "Hardware AutomaticGainControl enabled")
                    }
                }
            }

            audioRecord?.startRecording()
            isRecording = true
            setState(ButtonState.RECORDING)
            Log.d("FloatingButtonService", "Recording started (16kHz PCM VOICE_RECOGNITION)")

            val targetFile = audioFile!!
            recordingThread = Thread {
                writeAudioDataToWavFile(targetFile, bufferSize)
            }.apply { start() }

        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Start recording failed", e)
            releaseAudioResources()
        }
    }

    private fun writeAudioDataToWavFile(file: File, bufferSize: Int) {
        val data = ByteArray(bufferSize)
        val fos: FileOutputStream
        try {
            fos = FileOutputStream(file)
            // Reserve 44 bytes for WAV header
            fos.write(ByteArray(44))
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to create WAV file stream", e)
            return
        }

        var totalAudioLen = 0L
        try {
            while (isRecording) {
                val read = audioRecord?.read(data, 0, data.size) ?: 0
                if (read > 0) {
                    fos.write(data, 0, read)
                    totalAudioLen += read
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
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat (1 = PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
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
        setState(ButtonState.PROCESSING)

        audioFile?.let { file ->
            if (file.exists() && file.length() > 44) {
                apiClient.transcribe(file) { transcribedText ->
                    if (transcribedText != null) {
                        Log.d("FloatingButtonService", "Transcribed: $transcribedText")
                        mainHandler.post {
                            DictationAccessibilityService.getInstance()?.pasteText(transcribedText)
                        }
                    } else {
                        mainHandler.post { setState(ButtonState.HIDDEN) }
                    }
                }
            } else {
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
                CHANNEL_ID, "WhisperRemote Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperRemote")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        snapAnimator?.cancel()
        snapAnimator = null
        container?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error removing overlay", e)
            }
        }
        isRecording = false
        releaseAudioResources()
    }
}