// This file provides the full-screen 7-page First-Run Intro for Whisper Unchained.
// It is called by MainActivity upon first launch or when the user selects "Run setup again" in About.
// Inputs: SettingsRepository, ApiClient (for sandbox dictation & health check), system permissions, completion callback.
// Outputs: 7-stage interactive sandbox onboarding with segmented progress bar, in-place practice dictation sandbox,
//          mic permission requested on first practice mic tap, and Play-compliant prominent accessibility disclosure.
// Conforms to contracts/04-look-and-feel.md §4 and Play policy requirements in F-22, F-24, F-25.
// Reason for edit: Out-of-the-box offline model download & on-device practice dictation per contract 05 §2.7.
// Reason for edit: Branch battery optimization in Play flavor to open standard list settings without restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission per F-27.

package com.example.whisperremote.ui.intro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.example.whisperremote.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.whisperremote.ApiClient
import com.example.whisperremote.DictationAccessibilityService
import com.example.whisperremote.HealthResult
import com.example.whisperremote.R
import com.example.whisperremote.TranscriptionResult
import com.example.whisperremote.data.InjectionMode
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.data.TranscriptionTarget
import com.example.whisperremote.ui.theme.WhisperTokens
import com.example.whisperremote.util.ModelDownloadState
import com.example.whisperremote.util.OnDeviceModelManager
import com.example.whisperremote.util.OnDeviceTranscriptionEngine
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class PracticeAudioRecorder(private val cacheDir: File) {
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    val targetFile = File(cacheDir, "practice_sample.wav")

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, 2048)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }
            audioRecord = record
            record.startRecording()
            isRecording = true

            recordingThread = Thread {
                val data = ByteArray(bufferSize)
                var fos: FileOutputStream? = null
                var totalAudioLen = 0L
                try {
                    fos = FileOutputStream(targetFile)
                    fos.write(ByteArray(44)) // reserve 44-byte WAV header
                    while (isRecording) {
                        val rec = audioRecord
                        if (rec != null && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val read = rec.read(data, 0, data.size)
                            if (read > 0) {
                                fos.write(data, 0, read)
                                totalAudioLen += read
                            }
                        } else {
                            Thread.sleep(20)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("IntroScreen", "Error recording practice audio", e)
                } finally {
                    try {
                        fos?.close()
                    } catch (_: Exception) {}
                }
                writeWavHeader(targetFile, totalAudioLen, 16000, 1, 16)
            }.apply { start() }
            return true
        } catch (e: Exception) {
            Log.e("IntroScreen", "Failed to start practice recording", e)
            return false
        }
    }

    fun stop(): File? {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("IntroScreen", "Error stopping practice AudioRecord", e)
        }
        audioRecord = null
        try {
            recordingThread?.join(1000)
        } catch (_: Exception) {}
        recordingThread = null
        return if (targetFile.exists() && targetFile.length() > 44) targetFile else null
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
            Log.e("IntroScreen", "Error writing practice WAV header", e)
        }
    }
}

@Composable
fun IntroScreen(
    repository: SettingsRepository,
    apiClient: ApiClient,
    onScanQrCode: ((onSuccess: () -> Unit) -> Unit),
    onFinishIntro: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 1
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var currentPage by remember { mutableIntStateOf(initialPage) }
    val totalPages = 7

    val recorder = remember { PracticeAudioRecorder(context.cacheDir) }

    // On-device model manager & transcription engine for out-of-the-box intro practice per contract 05 §2.7
    val onDeviceModelManager = remember { OnDeviceModelManager(context) }
    val onDeviceEngine = remember { OnDeviceTranscriptionEngine(context, onDeviceModelManager, repository) }
    var isDefaultModelReady by remember {
        mutableStateOf(onDeviceModelManager.isModelReady(OnDeviceModelManager.DEFAULT_MODEL_ID))
    }
    var modelDownloadState by remember { mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle) }
    var activeCancelSignal by remember { mutableStateOf<AtomicBoolean?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            activeCancelSignal?.set(true)
            onDeviceEngine.release()
        }
    }

    // Practice sandbox states
    var practiceText by remember { mutableStateOf("") }
    var isPracticeRecording by remember { mutableStateOf(false) }
    var isPracticeTranscribing by remember { mutableStateOf(false) }
    var practiceSuccessCelebration by remember { mutableStateOf(false) }

    // Connect page state
    var isConnected by remember { mutableStateOf(repository.isServerVerified || repository.baseUrl.isNotBlank()) }
    var connectedBaseUrl by remember { mutableStateOf(repository.baseUrl) }
    var connectionStatusText by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(currentPage) {
        if (currentPage == 2) {
            isConnected = repository.isServerVerified || repository.baseUrl.isNotBlank()
            connectedBaseUrl = repository.baseUrl
        }
        if (currentPage == 3) {
            isDefaultModelReady = onDeviceModelManager.isModelReady(OnDeviceModelManager.DEFAULT_MODEL_ID)
        }
    }

    // Accessibility page state (false = disclosure card; true = visual step guide)
    var accessibilityAgreed by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val started = recorder.start()
            if (started) {
                isPracticeRecording = true
                practiceSuccessCelebration = false
                practiceText = "Listening... say 'testing, testing, testing'"
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPage > 1) {
                        IconButton(onClick = { currentPage-- }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "Step $currentPage of $totalPages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Segmented Progress Bar (§4)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 1..totalPages) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (i <= currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "IntroPageTransition"
            ) { page ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (page) {
                        // 1. Welcome (§4 item 1)
                        1 -> {
                            Spacer(modifier = Modifier.height(32.dp))
                            IntroIllustration(painter = painterResource(R.drawable.ic_mic), tint = WhisperTokens.BrandTeal)
                            Spacer(modifier = Modifier.height(32.dp))
                            Text(
                                text = "Talk instead of typing",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Dictate into any app with zero effort. Your voice goes directly to your own PC or private cloud key.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No account. No tracking. No ads.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Button(
                                onClick = { currentPage = 2 },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(26.dp)
                            ) {
                                Text("Get started")
                            }
                        }

                        // 2. Connect (§4 item 2, contract 05 §2.7)
                        2 -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            IntroIllustration(painter = painterResource(R.drawable.ic_qr_code_scanner), tint = WhisperTokens.FallbackAccent)
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Use your PC for best accuracy (optional)",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "For the most accurate results, scan the pairing code in Whisper Unchained Server on your PC. Or skip this step: dictation works on this phone without a PC.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (isConnected) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "✓ Connected to PC ($connectedBaseUrl)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WhisperTokens.BrandTeal,
                                    fontWeight = FontWeight.Medium
                                )
                            } else if (!connectionStatusText.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = connectionStatusText!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WhisperTokens.BrandTeal,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (isConnected) {
                                Button(
                                    onClick = { currentPage = 3 },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    Text("Continue to practice")
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedButton(
                                    onClick = {
                                        onScanQrCode {
                                            isConnected = true
                                            connectedBaseUrl = repository.baseUrl
                                            currentPage = 3
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan a different code")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        onScanQrCode {
                                            isConnected = true
                                            connectedBaseUrl = repository.baseUrl
                                            currentPage = 3
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan pairing code")
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                TextButton(
                                    onClick = {
                                        repository.transcriptionTarget = TranscriptionTarget.ON_DEVICE.id
                                        currentPage = 3
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Skip — transcribe on this phone")
                                }
                            }
                        }

                        // 3. Try it (Practice sandbox, §4 item 3 & contract 05 §2.7)
                        3 -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Try it out",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap the button below, speak a few words, then tap again to transcribe.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Mandatory Microphone Disclosure per F-22 item 3 & F-24 item 3
                            Text(
                                text = "Audio is recorded only while you tap the button, and goes where you chose: this phone, your PC or your cloud service.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // On-Device Model Download Banner (contract 05 §2.7)
                            val isDownloading = modelDownloadState is ModelDownloadState.Downloading
                            if (!isDefaultModelReady) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_shield),
                                                contentDescription = null,
                                                tint = WhisperTokens.BrandTeal,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Offline Model (136 MB)",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Download Parakeet TDT 110M to practice dictation and transcribe completely offline on this phone.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (isDownloading) {
                                            val dl = modelDownloadState as ModelDownloadState.Downloading
                                            // Reason for edit: Decimal MB calculations per F-32 matching contract 05
                                            val dlMb = (dl.totalBytesDownloaded / 1_000_000L).toInt()
                                            val totalMb = 136
                                            val progress = if (dl.totalBytesExpected > 0) dl.totalBytesDownloaded.toFloat() / dl.totalBytesExpected.toFloat() else 0f
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { progress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Downloading $dlMb of $totalMb MB...",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                TextButton(onClick = { activeCancelSignal?.set(true) }) {
                                                    Text("Cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = {
                                                    val cancelSig = AtomicBoolean(false)
                                                    activeCancelSignal = cancelSig
                                                    Thread {
                                                        onDeviceModelManager.downloadModel(
                                                            modelId = OnDeviceModelManager.DEFAULT_MODEL_ID,
                                                            allowMobileData = true,
                                                            cancelSignal = cancelSig
                                                        ) { state ->
                                                            mainHandler.post {
                                                                modelDownloadState = state
                                                                if (state is ModelDownloadState.Ready) {
                                                                    isDefaultModelReady = true
                                                                    repository.selectedOnDeviceModelId = OnDeviceModelManager.DEFAULT_MODEL_ID
                                                                    activeCancelSignal = null
                                                                    Toast.makeText(context, "Model ready! Tap the mic to practice.", Toast.LENGTH_SHORT).show()
                                                                } else if (state is ModelDownloadState.Failed || state is ModelDownloadState.Cancelled) {
                                                                    activeCancelSignal = null
                                                                }
                                                            }
                                                        }
                                                    }.start()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Download offline model (136 MB)")
                                            }
                                        }
                                    }
                                }
                            }

                            // Mock Message Sandbox Card
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "Practice box",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    OutlinedTextField(
                                        value = practiceText,
                                        onValueChange = { practiceText = it },
                                        placeholder = { Text("Your words will appear here.") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // App logo button for practice dictation
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isPracticeRecording) Color(0xFFD9694A).copy(alpha = 0.2f)
                                                     else WhisperTokens.BrandTeal.copy(alpha = 0.12f)
                                                )
                                                .clickable {
                                                    val hasMic = ContextCompat.checkSelfPermission(
                                                        context, Manifest.permission.RECORD_AUDIO
                                                    ) == PackageManager.PERMISSION_GRANTED
                                                    if (!hasMic) {
                                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    } else {
                                                        if (!isPracticeRecording) {
                                                            val started = recorder.start()
                                                            if (started) {
                                                                isPracticeRecording = true
                                                                practiceSuccessCelebration = false
                                                                practiceText = "Listening... say 'testing, testing, testing'"
                                                            } else {
                                                                practiceText = "Could not initialize microphone. Please check permissions."
                                                            }
                                                        } else {
                                                            isPracticeRecording = false
                                                            val audioFile = recorder.stop()
                                                            if (audioFile != null && audioFile.length() > 44) {
                                                                val useOnDevice = repository.transcriptionTarget == TranscriptionTarget.ON_DEVICE.id || repository.baseUrl.isBlank()
                                                                if (useOnDevice) {
                                                                    if (!isDefaultModelReady) {
                                                                        practiceText = "Please download the offline model above first to transcribe on this phone."
                                                                    } else {
                                                                        isPracticeTranscribing = true
                                                                        practiceText = "Transcribing on this phone..."
                                                                        Thread {
                                                                            try {
                                                                                if (!onDeviceEngine.isInitialized) {
                                                                                    onDeviceEngine.initialize(OnDeviceModelManager.DEFAULT_MODEL_ID)
                                                                                }
                                                                                val text = onDeviceEngine.transcribe(audioFile, removeFillers = repository.removeFillerSounds)
                                                                                mainHandler.post {
                                                                                    isPracticeTranscribing = false
                                                                                    practiceText = text.ifBlank { "(No speech detected - try speaking closer to the mic)" }
                                                                                    practiceSuccessCelebration = text.isNotBlank()
                                                                                }
                                                                            } catch (e: Exception) {
                                                                                Log.e("IntroScreen", "On-device practice transcription failed", e)
                                                                                mainHandler.post {
                                                                                    isPracticeTranscribing = false
                                                                                    practiceText = "Transcription failed: ${e.message}"
                                                                                }
                                                                            }
                                                                        }.start()
                                                                    }
                                                                } else {
                                                                    isPracticeTranscribing = true
                                                                    practiceText = "Transcribing audio with your PC..."
                                                                    apiClient.transcribeDetailed(audioFile) { result ->
                                                                        when (result) {
                                                                            is TranscriptionResult.Success -> {
                                                                                isPracticeTranscribing = false
                                                                                practiceText = result.text.ifBlank { "(No speech detected)" }
                                                                                practiceSuccessCelebration = true
                                                                            }
                                                                            is TranscriptionResult.Empty -> {
                                                                                isPracticeTranscribing = false
                                                                                practiceText = "(No speech detected - try speaking closer to the mic)"
                                                                                practiceSuccessCelebration = false
                                                                            }
                                                                            else -> {
                                                                                if (isDefaultModelReady) {
                                                                                    practiceText = "PC unreachable. Transcribing on this phone instead..."
                                                                                    Thread {
                                                                                        try {
                                                                                            if (!onDeviceEngine.isInitialized) {
                                                                                                onDeviceEngine.initialize(OnDeviceModelManager.DEFAULT_MODEL_ID)
                                                                                            }
                                                                                            val text = onDeviceEngine.transcribe(audioFile, removeFillers = repository.removeFillerSounds)
                                                                                            mainHandler.post {
                                                                                                isPracticeTranscribing = false
                                                                                                practiceText = "$text\n\n(Transcribed on this phone: PC unreachable)"
                                                                                                practiceSuccessCelebration = text.isNotBlank()
                                                                                            }
                                                                                        } catch (e: Exception) {
                                                                                            mainHandler.post {
                                                                                                isPracticeTranscribing = false
                                                                                                practiceText = "Transcription failed"
                                                                                            }
                                                                                        }
                                                                                    }.start()
                                                                                } else {
                                                                                    isPracticeTranscribing = false
                                                                                    practiceText = "Recorded ${(audioFile.length() - 44) / 1024} KB of audio!\nCould not reach server: ${(result as? TranscriptionResult.NetworkError)?.error?.localizedMessage ?: "Connection error"}"
                                                                                    practiceSuccessCelebration = true
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                practiceText = "Recording was too short. Tap again and speak a phrase."
                                                            }
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                painter = painterResource(
                                                    if (isPracticeRecording) R.drawable.whisper_remote_record else R.drawable.whisper_remote
                                                ),
                                                contentDescription = if (isPracticeRecording) "Stop recording" else "Tap here to practice speaking",
                                                modifier = Modifier.size(54.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        if (isPracticeTranscribing) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = WhisperTokens.BrandTeal
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Transcribing...",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = WhisperTokens.BrandTeal
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = if (isPracticeRecording) "← Tap to stop" else "← Tap here to practice",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isPracticeRecording) Color(0xFFD9694A) else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    if (practiceSuccessCelebration) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "✓ Practice dictation captured successfully!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = WhisperTokens.BrandTeal,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { currentPage = 4 },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(26.dp)
                            ) {
                                Text("Continue")
                            }
                        }

                        // 4. Appear on top (§4 item 4)
                        4 -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            IntroIllustration(icon = Icons.Default.PlayArrow, tint = Color(0xFF29A6B8))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Appear on top",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Whisper Unchained needs the 'Display over other apps' permission so the floating microphone button can overlay your apps when dictating.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                    currentPage = 5
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(26.dp)
                            ) {
                                Text("Allow overlay permission")
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            TextButton(
                                onClick = { currentPage = 5 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Not now")
                            }
                        }

                        // 5. Paste into apps (Prominent Accessibility Disclosure, §4 item 5 + F-22 item 4)
                        5 -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            IntroIllustration(painter = painterResource(R.drawable.ic_shield), tint = Color(0xFFE57E3B))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Paste into apps",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (!accessibilityAgreed) {
                                // Prominent Disclosure Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(18.dp)
                                    ) {
                                        Text(
                                            text = "How Whisper Unchained uses Accessibility",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Whisper Unchained uses Android's Accessibility Service solely to detect the active text field and insert your words at the cursor.\n\n" +
                                                    "• What we read: The active editable field to place your text.\n" +
                                                    "• What we NEVER touch: Password, PIN, and payment fields are never read or typed into.\n" +
                                                    "• Privacy guarantee: No keystrokes or field contents are stored or sent anywhere.\n\n" +
                                                    "You can turn this off anytime in Settings and use Clipboard mode instead.",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Affirmative Agree button (Back/home gesture does not count per F-22)
                                Button(
                                    onClick = {
                                        accessibilityAgreed = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    Text("Agree & Continue")
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                TextButton(
                                    onClick = {
                                        repository.injectionMode = InjectionMode.CLIPBOARD
                                        currentPage = 6
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Not now (use clipboard chip)")
                                }
                            } else {
                                // 3-Step Visual Guide after Agree
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    StepRow(step = "1", text = "Tap 'Open settings' below")
                                    Spacer(modifier = Modifier.height(12.dp))
                                    StepRow(step = "2", text = "Find 'Whisper Unchained' in Installed Apps")
                                    Spacer(modifier = Modifier.height(12.dp))
                                    StepRow(step = "3", text = "Turn ON the main switch (leave the shortcut switch OFF)")
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                        currentPage = 6
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    Text("Open Accessibility Settings")
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                TextButton(
                                    onClick = { currentPage = 6 },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Done / Skip")
                                }
                            }
                        }

                        // 6. Stay ready (§4 item 6)
                        6 -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            IntroIllustration(icon = Icons.Default.Notifications, tint = Color(0xFF38A169))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Stay ready",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "• Notifications keep Android from closing the app while you're in other apps, and show when recording is active.\n\n" +
                                        "• Unrestricted battery stops Android from putting the floating microphone to sleep so it is always ready when you tap it." +
                                        if (BuildConfig.FLAVOR == "play") "\n\nFind Whisper Unchained in the list and choose Not optimized / Unrestricted." else "",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Button(
                                onClick = {
                                    if (BuildConfig.FLAVOR == "play") {
                                        try {
                                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        try {
                                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                context.startActivity(intent)
                                            }
                                        }
                                    }
                                    currentPage = 7
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(26.dp)
                            ) {
                                Text("Configure battery exemption")
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            TextButton(
                                onClick = { currentPage = 7 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Skip")
                            }
                        }

                        // 7. What to expect (§4 item 7)
                        7 -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            IntroIllustration(icon = Icons.Default.Check, tint = WhisperTokens.BrandTeal)
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "You're all set!",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "The floating button appears on your screen.\n\n" +
                                        "• Tap once to start dictating.\n" +
                                        "• Tap again to stop and paste.\n" +
                                        "• Drag the button to place it anywhere you like.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Button(
                                onClick = {
                                    if (repository.baseUrl.isBlank() && isDefaultModelReady) {
                                        repository.transcriptionTarget = TranscriptionTarget.ON_DEVICE.id
                                    }
                                    repository.hasCompletedFirstRunChecklist = true
                                    onFinishIntro()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(26.dp)
                            ) {
                                Text("Finish setup")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroIllustration(
    tint: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    painter: androidx.compose.ui.graphics.painter.Painter? = null
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(48.dp)
            )
        } else if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
private fun StepRow(step: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(text = step, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
