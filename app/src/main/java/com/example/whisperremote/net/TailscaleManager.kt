// This file manages connectivity recovery and diagnostic state for Tailscale VPN.
// It is called by FloatingButtonService, IntroScreen, and TranscriptionScreen when health checks or transcription requests detect Tailscale VPN stalls or setup failures.
// Inputs: Application Context, checks for package "com.tailscale.ipn", network interface inspection, tracks restart attempt timestamps and counts.
// Outputs: Sends explicit broadcasts com.tailscale.ipn.DISCONNECT_VPN and CONNECT_VPN to com.tailscale.ipn/.IPNReceiver with 1.5s delay,
//          opens Tailscale app or Play Store listing, and provides Tailscale connection diagnostics.
// Implements ROADMAP §3.4 guardrails: max 1 auto-restart per 5 minutes, max 2 consecutive attempts.
// Reason for edit: Add Tailscale connection diagnosis, interface detection, and Play Store link per F-37.

package com.example.whisperremote.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

sealed class TailscaleDiagnosis {
    data object TailscaleNotInstalled : TailscaleDiagnosis()
    data object TailscaleNotConnected : TailscaleDiagnosis()
    data object ServerUnreachable : TailscaleDiagnosis()
}

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

        /**
         * Checks if the given URL or host is a Tailscale address (100.64.0.0/10 or *.ts.net).
         */
        fun isTailscaleAddress(hostOrUrl: String): Boolean {
            val clean = hostOrUrl.removePrefix("http://").removePrefix("https://").substringBefore(":").substringBefore("/").trim()
            if (clean.endsWith(".ts.net", ignoreCase = true)) return true
            val parts = clean.split(".")
            if (parts.size == 4) {
                val first = parts[0].toIntOrNull()
                val second = parts[1].toIntOrNull()
                if (first == 100 && second != null && second in 64..127) {
                    return true
                }
            }
            return false
        }
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
     * Checks if Tailscale is currently connected by checking for an active VPN/tun network interface with a 100.64.0.0/10 address.
     */
    fun isTailscaleConnected(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (iface in interfaces) {
                if (iface.isUp && (iface.name.contains("tun", ignoreCase = true) || iface.name.contains("tailscale", ignoreCase = true))) {
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                val first = parts[0].toIntOrNull()
                                val second = parts[1].toIntOrNull()
                                if (first == 100 && second != null && second in 64..127) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e("TailscaleManager", "Error inspecting network interfaces for Tailscale connection", e)
            false
        }
    }

    /**
     * Diagnoses connection failure according to F-37 priority ordering.
     */
    fun diagnoseConnectionFailure(targetUrl: String): TailscaleDiagnosis {
        val isTs = isTailscaleAddress(targetUrl)
        if (isTs) {
            if (!isTailscaleInstalled()) {
                return TailscaleDiagnosis.TailscaleNotInstalled
            }
            if (!isTailscaleConnected()) {
                return TailscaleDiagnosis.TailscaleNotConnected
            }
        }
        return TailscaleDiagnosis.ServerUnreachable
    }

    /**
     * Opens Google Play Store page for Tailscale app (com.tailscale.ipn).
     */
    fun openPlayStoreForTailscale() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Opens the Tailscale app directly.
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
}
