package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.atomikpanda.groundcontrol.R

/** Bundled JetBrains Mono for technical tokens (ids, slugs, branches, counts). */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

/** Prose typography — system sans default scale (tuned later if needed). */
val AppTypography = Typography()

/** Apply to identifier Text composables: `style = MonoStyle` or `fontFamily = JetBrainsMono`. */
val MonoStyle: TextStyle = AppTypography.bodySmall.copy(fontFamily = JetBrainsMono)
