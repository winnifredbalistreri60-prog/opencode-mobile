package ru.dredd20.opencode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF7CDA8A)
private val DarkBg = Color(0xFF0B1220)
private val DarkSurf = Color(0xFF121A2A)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF00391A),
    background = DarkBg,
    surface = DarkSurf,
    onBackground = Color(0xFFE6EDF7),
    onSurface = Color(0xFFE6EDF7),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B7A3F),
    background = Color(0xFFF4F7FB),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    // Dark by default; follow system when the user has a light theme.
    val scheme = if (isSystemInDarkTheme() || true) DarkColors else LightColors
    // Always dark for v0.1 (spec: тёмная тема). The `true` keeps it stable.
    MaterialTheme(colorScheme = DarkColors, content = content)
}
