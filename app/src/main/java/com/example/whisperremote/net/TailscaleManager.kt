// This file manages connectivity recovery for Tailscale VPN.
// It is called by FloatingButtonService when health checks or transcription requests detect Tailscale VPN stalls.
// Inputs: Application Context, checks for package "com.tailscale.ipn", tracks restart attempt timestamps and counts.
// Outputs: Sends explicit broadcasts com.tailscale.ipn.DISCONNECT_VPN and CONNECT_VPN to com.tailscale.ipn/.IPNReceiver with 1.5s delay.
// Implements ROADMAP §3.4 guardrails: max 1 auto-restart per 5 minutes, max 2 consecutive attempts.

package com.example.whisperremote.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class TailscaleManager(private val context: Context) {

    companion object {
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        const val IPN_RECEIVER = "com.tailscale.ipn.IPNReceiver"
        const val ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN"
        const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"

        private const val MIN_RESTART_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CONSECUTIVE_ATTEMPTS = 2

        private var lastRestartTimestampMs = 0L
        private var consecutiveAttempts = 0
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Checks if the Tailscale Android package is installed on the device.
     */
    fun isTailscaleInstalled(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Attempts to automatically restart the Tailscale VPN connection if guardrails permit.
     * Returns true if restart broadcast was dispatched, false if blocked by guardrails or not installed.
     */
    fun restartTailscaleIfAllowed(onComplete: (Boolean) -> Unit = {}): Boolean {
        if (!isTailscaleInstalled()) {
            Log.d("TailscaleManager", "Tailscale is not installed; skipping restart.")
            onComplete(false)
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastRestartTimestampMs < MIN_RESTART_INTERVAL_MS && consecutiveAttempts >= MAX_CONSECUTIVE_ATTEMPTS) {
            Log.w("TailscaleManager", "Tailscale auto-restart guardrail triggered ($consecutiveAttempts attempts within 5 minutes).")
            onComplete(false)
            return false
        }

        if (now - lastRestartTimestampMs >= MIN_RESTART_INTERVAL_MS) {
            consecutiveAttempts = 0
        }

        consecutiveAttempts++
        lastRestartTimestampMs = now
        executeRestart(onComplete)
        return true
    }

    /**
     * Force restart (used when user manually taps "Restart Tailscale" on error badge / notification).
     */
    fun forceRestart(onComplete: (Boolean) -> Unit = {}) {
        executeRestart(onComplete)
    }

    private fun executeRestart(onComplete: (Boolean) -> Unit) {
        Log.i("TailscaleManager", "Sending DISCONNECT_VPN broadcast to $TAILSCALE_PACKAGE...")
        try {
            val disconnectIntent = Intent(ACTION_DISCONNECT).apply {
                component = ComponentName(TAILSCALE_PACKAGE, IPN_RECEIVER)
            }
            context.sendBroadcast(disconnectIntent)

            // Wait 1.5 seconds per ROADMAP §3.4 then send CONNECT_VPN
            handler.postDelayed({
                try {
                    Log.i("TailscaleManager", "Sending CONNECT_VPN broadcast to $TAILSCALE_PACKAGE...")
                    val connectIntent = Intent(ACTION_CONNECT).apply {
                        component = ComponentName(TAILSCALE_PACKAGE, IPN_RECEIVER)
                    }
                    context.sendBroadcast(connectIntent)
                    onComplete(true)
                } catch (e: Exception) {
                    Log.e("TailscaleManager", "Failed to send CONNECT_VPN broadcast", e)
                    onComplete(false)
                }
            }, 1500)
        } catch (e: Exception) {
            Log.e("TailscaleManager", "Failed to send DISCONNECT_VPN broadcast", e)
            onComplete(false)
        }
    }

    /**
     * Opens the Tailscale app directly as a fallback if broadcast fails or has no effect.
     */
    fun openTailscaleApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Log.e("TailscaleManager", "Unable to find launch intent for $TAILSCALE_PACKAGE")
        }
    }
}
