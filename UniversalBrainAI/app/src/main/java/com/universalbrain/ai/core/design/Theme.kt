package com.universalbrain.ai.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Graphite background, green + electric blue accents.
private val Graphite = Color(0xFF14171C)
private val SurfaceDark = Color(0xFF1C2027)
private val SurfaceVariant = Color(0xFF242A33)
private val AccentGreen = Color(0xFF19E58C)
private val AccentBlue = Color(0xFF2E8BFF)
private val TextPrimary = Color(0xFFEDF1F5)
private val TextSecondary = Color(0xFF9AA5B1)

private val DarkColors = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Color(0xFF04120B),
    primaryContainer = SurfaceVariant,
    onPrimaryContainer = TextPrimary,
    secondary = AccentBlue,
    onSecondary = Color(0xFF04101F),
    secondaryContainer = SurfaceVariant,
    onSecondaryContainer = TextPrimary,
    background = Graphite,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
    outline = Color(0xFF2C333D)
)

@Composable
fun UniversalBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
