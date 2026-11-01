package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GlassDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = TextDark,
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFE2E8F0),
    secondary = NeonPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E1065),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = NeonCyan,
    onTertiary = TextDark,
    background = DeepObsidian,
    onBackground = TextPrimary,
    surface = DarkNavy,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = TextSecondary,
    outline = CardGlassBorder,
    outlineVariant = Color(0x22FFFFFF)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force modern dark glass theme by default
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GlassDarkColorScheme,
        typography = Typography,
        content = content
    )
}
