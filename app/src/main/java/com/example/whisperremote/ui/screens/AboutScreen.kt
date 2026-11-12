// This file provides the About subpage for Whisper Unchained.
// Updated to match F-23 site and privacy policy URLs.
// Reason for edit: Bump displayed build to Build 11 per F-26.
// It is called by HomeScreen.
// Inputs: Version info, GitHub links, callbacks to reopen First-Run Intro.
// Outputs: About screen with Privacy Policy link, Website link, Contact link, and "Run setup again" trigger.
// Conforms to contracts/04-look-and-feel.md §3.1 and Play policy requirements in F-22 & F-23.

package com.example.whisperremote.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperremote.BuildConfig
import com.example.whisperremote.ui.components.SettingsDivider
import com.example.whisperremote.ui.components.SettingsGroup
import com.example.whisperremote.ui.components.SettingsNavRow
import com.example.whisperremote.ui.theme.WhisperTokens

object AboutConstants {
    const val PRIVACY_POLICY_URL = "https://whisperunchained.cyrus-studio.com/privacy/"
    const val WEBSITE_URL = "https://whisperunchained.cyrus-studio.com"
    const val CONTACT_EMAIL_URL = "mailto:Dominic@cyrus-studio.com"
    const val GITHUB_REPO_URL = "https://github.com/Dominic-Shirazi/Whisper-Unchained-Android"
    const val SERVER_REPO_URL = "https://github.com/Dominic-Shirazi/Whisper-Unchained-Server"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onLaunchIntro: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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
                            text = "Version 1.2.0 (Build 11) · ${BuildConfig.FLAVOR.replaceFirstChar { it.uppercase() }}",
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
            item {
                SettingsGroup(
                    header = "About & Privacy",
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
                }
            }

            // Group 3: Onboarding Reset
            item {
                SettingsGroup(
                    header = "Setup",
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
                    }
                }
            }
        }
    }
}
