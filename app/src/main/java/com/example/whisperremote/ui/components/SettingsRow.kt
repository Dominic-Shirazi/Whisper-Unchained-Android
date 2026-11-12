// This file provides row components for settings lists within SettingsGroup.
// It is called by HomeScreen and all subpage screens in Whisper Unchained.
// Inputs: Title, optional subtitle (rendered in accent color for values), optional leading icon,
//         optional trailing widget (Switch, Arrow, Radio), click actions.
// Outputs: Touch-accessible rows (min height 56dp for 1 line, 72dp for 2 lines) with hairline dividers.
// It supports One UI-style colored circle icons on Home, split rows, and color-blind safe indicators.

package com.example.whisperremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.whisperremote.ui.theme.WhisperTokens

@Composable
fun SettingsDivider(
    modifier: Modifier = Modifier,
    insetStart: Boolean = true
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (insetStart) 56.dp else 20.dp, end = 20.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun SettingsNavRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    iconBackgroundColor: Color? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (subtitle.isNullOrBlank()) WhisperTokens.SingleLineRowMinHeight
                else WhisperTokens.TwoLineRowMinHeight
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = WhisperTokens.RowHorizontalPadding, vertical = WhisperTokens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null || iconPainter != null) {
            if (iconBackgroundColor != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(iconBackgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconPainter != null) {
                        Icon(
                            painter = iconPainter,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                if (iconPainter != null) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    iconBackgroundColor: Color? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (subtitle.isNullOrBlank()) WhisperTokens.SingleLineRowMinHeight
                else WhisperTokens.TwoLineRowMinHeight
            )
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = WhisperTokens.RowHorizontalPadding, vertical = WhisperTokens.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null || iconPainter != null) {
            if (iconBackgroundColor != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(iconBackgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconPainter != null) {
                        Icon(
                            painter = iconPainter,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                if (iconPainter != null) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun SettingsSplitRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRowClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    iconBackgroundColor: Color? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (subtitle.isNullOrBlank()) WhisperTokens.SingleLineRowMinHeight
                else WhisperTokens.TwoLineRowMinHeight
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onRowClick)
                .padding(
                    start = WhisperTokens.RowHorizontalPadding,
                    top = WhisperTokens.RowVerticalPadding,
                    bottom = WhisperTokens.RowVerticalPadding,
                    end = 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null || iconPainter != null) {
                if (iconBackgroundColor != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(iconBackgroundColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (iconPainter != null) {
                            Icon(
                                painter = iconPainter,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    if (iconPainter != null) {
                        Icon(
                            painter = iconPainter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        VerticalDivider(
            modifier = Modifier
                .height(32.dp)
                .width(0.5.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
