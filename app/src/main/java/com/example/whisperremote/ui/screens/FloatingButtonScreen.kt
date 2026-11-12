// This file provides the Floating button behavior settings subpage for Whisper Unchained.
// It is called by HomeScreen.
// Inputs: SettingsRepository (startOnBoot, autoRestartTailscale), TailscaleManager.
// Outputs: Switches for auto-boot start, Tailscale auto-restart (gated by Tailscale installation), and overlay management.
// Conforms to contracts/04-look-and-feel.md §3.3.
// Reason for edit: Verify RECORD_AUDIO before starting FloatingButtonService per F-26.

package com.example.whisperremote.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.whisperremote.FloatingButtonService
import com.example.whisperremote.net.TailscaleManager
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsSwitchRow
import com.example.whisperremote.ui.theme.WhisperTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingButtonScreen(
    repository: SettingsRepository,
    tailscaleManager: TailscaleManager,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var startOnBoot by remember { mutableStateOf(repository.startOnBoot) }
    var autoRestartTailscale by remember { mutableStateOf(repository.autoRestartTailscale) }

    val isTailscaleInstalled = remember { tailscaleManager.isTailscaleInstalled() }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Display over other apps permission required", Toast.LENGTH_SHORT).show()
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } else {
                val intent = Intent(context, FloatingButtonService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    Toast.makeText(context, "Floating button started", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("FloatingButtonScreen", "Failed to start service", e)
                }
            }
        } else {
            Toast.makeText(context, "Microphone permission is required to start the floating button", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Floating button") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
        ) {
            // Group 1: Device Behavior
            item {
                SettingsGroup(
                    header = "Behavior",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsSwitchRow(
                        title = "Start on phone reboot",
                        subtitle = "Automatically show the floating mic button when the phone boots up",
                        checked = startOnBoot,
                        onCheckedChange = {
                            startOnBoot = it
                            repository.startOnBoot = it
                        }
                    )
                }
            }

            // Group 2: Tailscale Auto-Recovery (Shown only if Tailscale is installed per §3.3)
            if (isTailscaleInstalled) {
                item {
                    SettingsGroup(
                        header = "Tailscale Connectivity",
                        footer = "If VPN drops or Head PC becomes unreachable, the app sends restart intents to Tailscale to restore the connection.",
                        modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                    ) {
                        SettingsSwitchRow(
                            title = "Auto-restart Tailscale when it stalls",
                            subtitle = "Dispatches connect/disconnect broadcasts if VPN transport drops",
                            checked = autoRestartTailscale,
                            onCheckedChange = {
                                autoRestartTailscale = it
                                repository.autoRestartTailscale = it
                            }
                        )
                    }
                }
            }

            // Group 3: Service Controls
            item {
                SettingsGroup(
                    header = "Service controls",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Button(
                            onClick = {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else if (!Settings.canDrawOverlays(context)) {
                                    Toast.makeText(context, "Display over other apps permission required", Toast.LENGTH_SHORT).show()
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                } else {
                                    val intent = Intent(context, FloatingButtonService::class.java)
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(intent)
                                        } else {
                                            context.startService(intent)
                                        }
                                        Toast.makeText(context, "Floating button restarted", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Log.e("FloatingButtonScreen", "Failed to start service", e)
                                        Toast.makeText(context, "Could not start service: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text("Restart floating button")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, FloatingButtonService::class.java)
                                context.stopService(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("Stop floating button service")
                        }
                    }
                }
            }
        }
    }
}
