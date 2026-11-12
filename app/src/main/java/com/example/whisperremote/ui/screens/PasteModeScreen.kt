// This file provides the Paste into apps settings subpage for Whisper Unchained.
// It is called by HomeScreen.
// Inputs: SettingsRepository (injectionMode), system accessibility service status.
// Outputs: Selection between Accessibility auto-paste and Clipboard chip, with re-showable prominent disclosure
//          and shortcut to Android Accessibility Settings. Conforms to contracts/04-look-and-feel.md §3.3 and F-22.
// Reason for edit: Automatically switch injectionMode to ACCESSIBILITY when service is enabled on resume per F-36 item 6.

package com.example.whisperremote.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperremote.DictationAccessibilityService
import com.example.whisperremote.data.InjectionMode
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.theme.WhisperTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteModeScreen(
    repository: SettingsRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var injectionMode by remember { mutableStateOf(repository.injectionMode) }
    var showDisclosureDialog by remember { mutableStateOf(false) }

    val isAccessibilityActive = DictationAccessibilityService.isEnabled(context)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (DictationAccessibilityService.isEnabled(context)) {
                    repository.injectionMode = InjectionMode.ACCESSIBILITY
                    injectionMode = InjectionMode.ACCESSIBILITY
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paste into apps") },
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
            // Group 1: Injection Mode Selection
            item {
                SettingsGroup(
                    header = "How your words get into apps",
                    footer = if (injectionMode == InjectionMode.ACCESSIBILITY) {
                        "Accessibility allows seamless automatic typing into your active text field."
                    } else {
                        "Clipboard mode copies the transcription so you can tap the 1-tap paste chip on your keyboard."
                    },
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    // Option 1: Accessibility
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                injectionMode = InjectionMode.ACCESSIBILITY
                                repository.injectionMode = InjectionMode.ACCESSIBILITY
                            }
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = injectionMode == InjectionMode.ACCESSIBILITY,
                            onClick = {
                                injectionMode = InjectionMode.ACCESSIBILITY
                                repository.injectionMode = InjectionMode.ACCESSIBILITY
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Paste automatically (Accessibility)",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAccessibilityActive) "Status: Allowed ✓" else "Status: Not allowed (requires permission)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAccessibilityActive) WhisperTokens.BrandTeal else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    SettingsDivider(insetStart = false)

                    // Option 2: Clipboard
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                injectionMode = InjectionMode.CLIPBOARD
                                repository.injectionMode = InjectionMode.CLIPBOARD
                            }
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = injectionMode == InjectionMode.CLIPBOARD,
                            onClick = {
                                injectionMode = InjectionMode.CLIPBOARD
                                repository.injectionMode = InjectionMode.CLIPBOARD
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clipboard chip",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Copies to clipboard; 1-tap paste chip appears on keyboard",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Group 2: Actions & Permissions
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text("Open Accessibility Settings")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { showDisclosureDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("View Accessibility disclosure")
                        }
                    }
                }
            }
        }
    }

    // Prominent Accessibility Disclosure Dialog (Re-showable per Play Policy & F-22)
    if (showDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showDisclosureDialog = false },
            title = {
                Text(
                    text = "How Whisper Unchained uses Accessibility",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Whisper Unchained uses the Android Accessibility Service solely to detect the currently focused text field and paste your dictated text at your cursor.\n\n" +
                                "• What we read: The active editable text field to place your words.\n" +
                                "• What we NEVER touch: Password, PIN, and credit card fields are never accessed or typed into.\n" +
                                "• Privacy guarantee: Nothing is stored or transmitted anywhere except your audio directly to your chosen transcription server.\n\n" +
                                "You can turn this off at any time and use Clipboard mode instead.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosureDialog = false
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosureDialog = false }) {
                    Text("Close")
                }
            },
            shape = RoundedCornerShape(26.dp)
        )
    }
}
