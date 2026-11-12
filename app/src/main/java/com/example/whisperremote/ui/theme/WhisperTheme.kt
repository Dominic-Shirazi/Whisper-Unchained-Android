// This file defines the Material 3 theme and One UI design tokens for Whisper Unchained.
// It is called by MainActivity, IntroActivity, and all Compose UI components.
// Inputs: System dark mode state, Android 12+ Dynamic Color availability, LocalContext.
// Outputs: MaterialTheme with custom color scheme, typography, and shapes conforming to contracts/04-look-and-feel.md.
// Special characteristics: Pure black ground (#000000) in dark mode, calm blue fallback (#3E6FD8), 26dp rounded group corners,
// zero borders/shadows/glows, and strict red/green color-blind safety (state is never color alone).

package com.example.whisperremote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One UI / Whisper Unchained tokens per contracts/04-look-and-feel.md §5
object WhisperTokens {
    val GroupCornerRadius = 26.dp
    val ScreenGutter = 12.dp
    val GroupGap = 16.dp
    val HelperTextGap = 8.dp
    val RowHorizontalPadding = 20.dp
    val RowVerticalPadding = 14.dp
    val SingleLineRowMinHeight = 56.dp
    val TwoLineRowMinHeight = 72.dp
    val SplitDividerWidth = 1.dp

    // Brand mic color (kept invariant across light & dark themes)
    val BrandTeal = Color(0xFF2FB79A)

    // Fallback seed accent (calm One UI blue)
    val FallbackAccent = Color(0xFF3E6FD8)
    val FallbackAccentDark = Color(0xFF6B95F2)

    // Dark mode pure black ground
    val DarkBackground = Color(0xFF000000)
    val DarkSurfaceContainer = Color(0xFF1C1C1E)
    val DarkSurfaceContainerHigh = Color(0xFF252528)
    val DarkTextPrimary = Color(0xFFFFFFFF)
    val DarkTextSecondary = Color(0xFF8E8E93)
    val DarkDivider = Color(0x33545458)

    // Light mode surface tokens
    val LightBackground = Color(0xFFF2F2F4)
    val LightSurfaceContainer = Color(0xFFFCFCFF)
    val LightSurfaceContainerHigh = Color(0xFFEBEBF0)
    val LightTextPrimary = Color(0xFF111111)
    val LightTextSecondary = Color(0xFF76767C)
    val LightDivider = Color(0x22000000)
}

private val LightColorScheme = lightColorScheme(
    primary = WhisperTokens.FallbackAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E8FF),
    onPrimaryContainer = Color(0xFF001946),
    background = WhisperTokens.LightBackground,
    onBackground = WhisperTokens.LightTextPrimary,
    surface = WhisperTokens.LightSurfaceContainer,
    onSurface = WhisperTokens.LightTextPrimary,
    surfaceVariant = WhisperTokens.LightSurfaceContainerHigh,
    onSurfaceVariant = WhisperTokens.LightTextSecondary,
    outline = Color(0xFFC7C7CC),
    outlineVariant = WhisperTokens.LightDivider
)

private val DarkColorScheme = darkColorScheme(
    primary = WhisperTokens.FallbackAccentDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1F3870),
    onPrimaryContainer = Color(0xFFD6E2FF),
    background = WhisperTokens.DarkBackground,
    onBackground = WhisperTokens.DarkTextPrimary,
    surface = WhisperTokens.DarkSurfaceContainer,
    onSurface = WhisperTokens.DarkTextPrimary,
    surfaceVariant = WhisperTokens.DarkSurfaceContainerHigh,
    onSurfaceVariant = WhisperTokens.DarkTextSecondary,
    outline = Color(0xFF3A3A3C),
    outlineVariant = WhisperTokens.DarkDivider
)

val WhisperShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(WhisperTokens.GroupCornerRadius),
    large = RoundedCornerShape(WhisperTokens.GroupCornerRadius),
    extraLarge = RoundedCornerShape(32.dp)
)

val WhisperTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

@Composable
fun WhisperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                // Ensure pure black background even with dynamic dark color scheme
                dynamicDarkColorScheme(context).copy(
                    background = WhisperTokens.DarkBackground,
                    surface = WhisperTokens.DarkSurfaceContainer,
                    surfaceVariant = WhisperTokens.DarkSurfaceContainerHigh,
                    outlineVariant = WhisperTokens.DarkDivider
                )
            } else {
                dynamicLightColorScheme(context).copy(
                    background = WhisperTokens.LightBackground,
                    surface = WhisperTokens.LightSurfaceContainer,
                    surfaceVariant = WhisperTokens.LightSurfaceContainerHigh,
                    outlineVariant = WhisperTokens.LightDivider
                )
            }
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WhisperTypography,
        shapes = WhisperShapes,
        content = content
    )
}
