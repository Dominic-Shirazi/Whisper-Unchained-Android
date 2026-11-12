// This file provides the prominent status card composable at the top of Whisper Unchained's home screen.
// It is called by HomeScreen (inspired by Samsung One UI's Samsung Account card).
// Inputs: Connection status (connected, model, paused, error), setup completion state, click listener.
// Outputs: Touch-accessible status card with informative icon and text indicators (color-blind safe).
// Tapping the card opens the Transcription screen (or launches the First-Run Intro if setup is pending).

package com.example.whisperremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.whisperremote.R
import com.example.whisperremote.ui.theme.WhisperTokens

sealed interface StatusCardState {
    data class IncompleteSetup(val stepsLeft: Int, val totalSteps: Int = 4) : StatusCardState
    data class Connected(val targetName: String, val model: String) : StatusCardState
    data class Cloud(val providerName: String, val model: String) : StatusCardState
    data class Paused(val message: String) : StatusCardState
    data class Disconnected(val reason: String) : StatusCardState
}

private data class CardIconData(
    val iconVector: ImageVector? = null,
    val iconPainter: Painter? = null,
    val iconBg: Color,
    val title: String,
    val subtitle: String
)

@Composable
fun StatusCard(
    state: StatusCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardData = when (state) {
        is StatusCardState.IncompleteSetup -> {
            CardIconData(
                iconVector = Icons.Default.Info,
                iconBg = WhisperTokens.FallbackAccent,
                title = "Finish setup (${state.stepsLeft} of ${state.totalSteps} steps left)",
                subtitle = "Tap to complete initial setup and test dictation"
            )
        }
        is StatusCardState.Connected -> {
            CardIconData(
                iconPainter = painterResource(R.drawable.ic_computer),
                iconBg = WhisperTokens.BrandTeal,
                title = "Connected: ${state.targetName}",
                subtitle = state.model
            )
        }
        is StatusCardState.Cloud -> {
            CardIconData(
                iconPainter = painterResource(R.drawable.ic_cloud),
                iconBg = WhisperTokens.FallbackAccent,
                title = "Using ${state.providerName} (Cloud)",
                subtitle = state.model
            )
        }
        is StatusCardState.Paused -> {
            CardIconData(
                iconVector = Icons.Default.Warning,
                iconBg = Color(0xFFD9A441), // Accessible warm amber
                title = "Dictation Paused on PC",
                subtitle = state.message.ifBlank { "Ready to resume" }
            )
        }
        is StatusCardState.Disconnected -> {
            CardIconData(
                iconVector = Icons.Default.Warning,
                iconBg = Color(0xFFD9694A), // Muted coral red
                title = "Not connected yet",
                subtitle = state.reason.ifBlank { "Tap to scan QR or configure server" }
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WhisperTokens.ScreenGutter)
            .clip(RoundedCornerShape(WhisperTokens.GroupCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(cardData.iconBg.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (cardData.iconPainter != null) {
                    Icon(
                        painter = cardData.iconPainter,
                        contentDescription = null,
                        tint = cardData.iconBg,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (cardData.iconVector != null) {
                    Icon(
                        imageVector = cardData.iconVector,
                        contentDescription = null,
                        tint = cardData.iconBg,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cardData.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = cardData.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
