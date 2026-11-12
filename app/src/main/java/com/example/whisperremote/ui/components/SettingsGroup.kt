// This file provides the grouped container composable for Whisper Unchained settings.
// It is called by all settings screens (HomeScreen, TranscriptionScreen, AiEditingScreen, etc.).
// Inputs: Optional header title, optional footer helper text, content Composable slot.
// Outputs: A borderless, shadowless rounded container with 26dp corners conforming to contracts/04-look-and-feel.md.
// It places helper text between groups (8dp below the group) rather than inside the group.

package com.example.whisperremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.whisperremote.ui.theme.WhisperTokens

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WhisperTokens.ScreenGutter)
    ) {
        if (!header.isNullOrBlank()) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WhisperTokens.GroupCornerRadius))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            content()
        }

        if (!footer.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(WhisperTokens.HelperTextGap))
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp)
            )
        }
    }
}
