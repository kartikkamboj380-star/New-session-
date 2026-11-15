package com.multispace.manager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1A2330),
    onPrimaryContainer = Color(0xFFD8D2FF),
    secondary = Color(0xFF2DE2C5),
    onSecondary = Color(0xFF06231D),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFF2F6FA),
    surface = Color(0xFF121821),
    onSurface = Color(0xFFF2F6FA),
    surfaceVariant = Color(0xFF1A2330),
    onSurfaceVariant = Color(0xFF9AA7B4),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
    outline = Color(0xFF2A3442)
)

@Composable
fun MultiSpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
