// This file is a broadcast receiver that listens for device startup.
// It is called by the Android OS when the device completes booting (android.intent.action.BOOT_COMPLETED).
// Inputs: Intent with ACTION_BOOT_COMPLETED, checks SettingsRepository.startOnBoot and Settings.canDrawOverlays.
// Outputs: Automatically starts FloatingButtonService in foreground mode if user enabled boot startup.
// Reason for edit: Verify RECORD_AUDIO permission and catch foreground start restrictions quietly on boot per F-26.

package com.example.whisperremote

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whisperremote.data.SettingsRepository

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val repository = SettingsRepository(context)
            if (!repository.startOnBoot) {
                Log.d("BootReceiver", "Start on boot is disabled in settings; ignoring BOOT_COMPLETED.")
                return
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w("BootReceiver", "RECORD_AUDIO permission not granted; skipping FloatingButtonService on boot quietly.")
                return
            }

            if (!Settings.canDrawOverlays(context)) {
                Log.w("BootReceiver", "Overlay permission not granted; cannot start FloatingButtonService on boot.")
                return
            }

            Log.i("BootReceiver", "Device boot completed. Auto-starting FloatingButtonService...")
            val serviceIntent = Intent(context, FloatingButtonService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Failed to start FloatingButtonService on boot: ${e.message}")
            }
        }
    }
}
