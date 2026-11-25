// This file provides the About subpage for Whisper Unchained.
// Updated to match F-23 site and privacy policy URLs.
// Reason for edit: Add account-free "Delete all local data" action and canonical Accessibility disclosure text per F-64 §3 & §5.
// Reason for edit: Add NVIDIA Open Model License attribution and dynamic version per F-57.
// Reason for edit: Add open source model attribution link (Parakeet CC-BY-4.0, sherpa-onnx Apache-2.0, Silero MIT) per contract 05 §2.2.
// It is called by HomeScreen.
// Inputs: Version info, GitHub links, callbacks to reopen First-Run Intro.
// Outputs: About screen with Privacy Policy link, Website link, Contact link, "Run setup again" trigger, and local data deletion.
// Conforms to contracts/04-look-and-feel.md §3.1 and Play policy requirements in F-22, F-23 & F-64.

package com.example.whisperremote.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperremote.BuildConfig
import com.example.whisperremote.data.SettingsRepository
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsNavRow
import com.example.whisperremote.ui.theme.WhisperTokens

object AboutConstants {
    // Placeholder / canonical Privacy Policy URL constant (update here if URL changes per F-64 §5)
    const val PRIVACY_POLICY_URL = "https://whisperunchained.cyrus-studio.com/privacy/"
    const val WEBSITE_URL = "https://whisperunchained.cyrus-studio.com"
    const val CONTACT_EMAIL_URL = "mailto:Dominic@cyrus-studio.com"
    const val GITHUB_REPO_URL = "https://github.com/Dominic-Shirazi/Whisper-Unchained-Android"
    const val SERVER_REPO_URL = "https://github.com/Dominic-Shirazi/Whisper-Unchained-Server"

    const val ACCESSIBILITY_DISCLOSURE_TITLE = "How Whisper Unchained uses Accessibility"
    const val ACCESSIBILITY_DISCLOSURE_TEXT =
        "Whisper Unchained uses the Android Accessibility Service solely to detect when an editable text box is focused and to insert or replace text at your cursor.\n\n" +
        "• Events & window content: Listens for focus, window state, content, and text selection changes (TYPE_VIEW_FOCUSED, TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_TEXT_SELECTION_CHANGED) with window content retrieval enabled.\n" +
        "• What we read & do: Reads the active editable field's text and selection boundaries so dictated text is spliced at your cursor (via ACTION_PASTE, ACTION_SET_TEXT, and ACTION_SET_SELECTION), or so highlighted text can be sent to your configured AI editor when you explicitly ask the AI to edit a selection.\n" +
        "• What we NEVER touch: Password, PIN, and credit-card/payment fields are strictly excluded and never read or modified.\n" +
        "• Privacy guarantee: No background screen reading, keystroke logging, analytics, or third-party data sharing ever occurs.\n\n" +
        "You can decline or turn this off at any time and use Clipboard mode instead."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onLaunchIntro: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            // Group 1: Version & Product Details
            item {
                SettingsGroup(
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = if (BuildConfig.FLAVOR == "full") "Whisper Unchained Full" else "Whisper Unchained",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) · ${BuildConfig.FLAVOR.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Talk instead of typing, in any app. Free and open source, with no account and no tracking.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Group 2: Privacy Policy & Legal Links (Play Policy requirement per F-22 & F-23)
            // Group 2: About & Privacy
            // Reason for edit: Add OpenAI non-affiliation notice in small print below the license link per F-82
            item {
                SettingsGroup(
                    header = "About & Privacy",
                    footer = "Whisper Unchained is an independent project. It is not affiliated with, endorsed by or sponsored by OpenAI. \"Whisper\" is the name of the open-source speech recognition model OpenAI released.",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    SettingsNavRow(
                        title = "Website",
                        subtitle = "whisperunchained.cyrus-studio.com",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AboutConstants.WEBSITE_URL))
                            context.startActivity(intent)
                        }
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Contact",
                        subtitle = "Dominic@cyrus-studio.com",
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(AboutConstants.CONTACT_EMAIL_URL))
                            context.startActivity(intent)
                        }
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Privacy policy",
                        subtitle = "Read our strict zero-data-collection policy",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AboutConstants.PRIVACY_POLICY_URL))
                            context.startActivity(intent)
                        }
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Source code (Android app)",
                        subtitle = "github.com/Dominic-Shirazi/Whisper-Unchained-Android",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AboutConstants.GITHUB_REPO_URL))
                            context.startActivity(intent)
                        }
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Desktop server (Windows)",
                        subtitle = "github.com/Dominic-Shirazi/Whisper-Unchained-Server",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AboutConstants.SERVER_REPO_URL))
                            context.startActivity(intent)
                        }
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        title = "Open source & model licenses",
                        subtitle = "Licensed by NVIDIA under NVIDIA Open Model License · CC-BY-4.0 · Apache-2.0 · MIT",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${AboutConstants.GITHUB_REPO_URL}/blob/master/ATTRIBUTIONS.md"))
                            context.startActivity(intent)
                        }
                    )
                }
            }

            // Group 3: Onboarding Reset & Local Data Deletion (F-64 §5)
            item {
                SettingsGroup(
                    header = "Setup & Local Data",
                    footer = "Delete all local data removes your saved server pairing, API keys, cached words/skills, and downloaded offline models from this phone.",
                    modifier = Modifier.padding(bottom = WhisperTokens.GroupGap)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Button(
                            onClick = onLaunchIntro,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text("Run setup intro again")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showDeleteConfirmDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFD9694A)
                            )
                        ) {
                            Text("Delete all local data")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    text = "This permanently deletes all locally stored settings, paired server addresses, encrypted API keys, cached words and skills, and downloaded offline speech models from this phone. No account is required.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        SettingsRepository(context).clearAllLocalData(context)
                        Toast.makeText(context, "All local data deleted", Toast.LENGTH_SHORT).show()
                        onLaunchIntro()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD9694A),
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete data")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
