package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val DarkScheme = darkColorScheme(
    primary = Palette.darkQuestion,
    onPrimary = Palette.darkBackground,
    background = Palette.darkBackground,
    onBackground = Palette.darkText,
    surface = Palette.darkSurface,
    onSurface = Palette.darkText,
    surfaceVariant = Palette.darkElevated,
    onSurfaceVariant = Palette.darkMuted,
    outline = Palette.darkDivider,
    error = Palette.darkError,
)

private val LightScheme = lightColorScheme(
    primary = Palette.lightQuestion,
    onPrimary = Palette.lightSurface,
    background = Palette.lightBackground,
    onBackground = Palette.lightText,
    surface = Palette.lightSurface,
    onSurface = Palette.lightText,
    surfaceVariant = Palette.lightElevated,
    onSurfaceVariant = Palette.lightMuted,
    outline = Palette.lightDivider,
    error = Palette.lightError,
)

/** Semantic accent roles for the active scheme; read via `LocalSemanticColors.current`. */
val LocalSemanticColors = staticCompositionLocalOf { SemanticDark }

@Composable
fun GroundControlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSemanticColors provides if (dark) SemanticDark else SemanticLight) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
