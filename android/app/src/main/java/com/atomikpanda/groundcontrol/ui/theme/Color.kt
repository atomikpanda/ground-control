package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.ui.graphics.Color

/** Dracula accents on near-black (dark) + contrast-tuned (light). Single source of truth. */
object Palette {
    // dark
    val darkBackground = Color(0xFF0A0E14)
    val darkSurface = Color(0xFF12161F)
    val darkElevated = Color(0xFF1A1F2B)
    val darkDivider = Color(0xFF2A2F3C)
    val darkText = Color(0xFFF8F8F2)
    // Muted secondary text. Lightened from 0xFF6272A4 (~3.85:1 on darkSurface, below WCAG AA) to
    // clear the 4.5:1 minimum — it carries a lot of real content (card meta, timestamps, read-state).
    val darkMuted = Color(0xFF7A88BE)
    val darkApproval = Color(0xFF50FA7B)
    val darkQuestion = Color(0xFF8BE9FD)
    val darkBlocker = Color(0xFFFFB86C)
    val darkError = Color(0xFFFF5555)
    val darkChips = listOf(Color(0xFFFF79C6), Color(0xFFBD93F9), Color(0xFF8BE9FD), Color(0xFF50FA7B))
    // light
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightElevated = Color(0xFFF1F5F9)
    val lightDivider = Color(0xFFE2E8F0)
    val lightText = Color(0xFF1E2230)
    val lightMuted = Color(0xFF64748B)
    val lightApproval = Color(0xFF16A34A)
    val lightQuestion = Color(0xFF0E7490)
    val lightBlocker = Color(0xFFB45309)
    val lightError = Color(0xFFDC2626)
    val lightChips = listOf(Color(0xFFDB2777), Color(0xFF7C3AED), Color(0xFF0E7490), Color(0xFF16A34A))
}

/** Semantic accent roles for one scheme. */
data class SemanticColors(
    val approval: Color,
    val question: Color,
    val blocker: Color,
    val error: Color,
    val muted: Color,
    val chipHues: List<Color>,
)

val SemanticDark = SemanticColors(
    approval = Palette.darkApproval, question = Palette.darkQuestion,
    blocker = Palette.darkBlocker, error = Palette.darkError,
    muted = Palette.darkMuted, chipHues = Palette.darkChips,
)
val SemanticLight = SemanticColors(
    approval = Palette.lightApproval, question = Palette.lightQuestion,
    blocker = Palette.lightBlocker, error = Palette.lightError,
    muted = Palette.lightMuted, chipHues = Palette.lightChips,
)

/** Stable per-workspace chip hue: same connection id → same hue across sessions. */
fun chipHue(connectionId: String, colors: SemanticColors): Color =
    colors.chipHues[(connectionId.hashCode() and Int.MAX_VALUE) % colors.chipHues.size]
