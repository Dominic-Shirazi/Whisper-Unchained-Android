package com.example.whisperremote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiUrlEditText: EditText
    private val PREFS_NAME = "WhisperRemotePrefs"
    private val KEY_API_URL = "api_url"

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
            checkOverlayPermission(false) // Don't finish, we want settings
        } else {
            Toast.makeText(this, "Permissions required for dictation", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiUrlEditText = findViewById(R.id.apiUrlEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val openAccessibilityButton = findViewById<Button>(R.id.openAccessibilityButton)
        val enableInternalAudioButton = findViewById<Button>(R.id.enableInternalAudioButton)

        // Load saved URL
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_API_URL, "http://your-server-ip:5000/transcribe")
        apiUrlEditText.setText(savedUrl)

        saveButton.setOnClickListener {
            val newUrl = apiUrlEditText.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                prefs.edit().putString(KEY_API_URL, newUrl).apply()
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
                // Start/Restart service with new URL
                checkPermissions()
            }
        }

        openAccessibilityButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        enableInternalAudioButton.setOnClickListener {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        checkPermissions()
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
