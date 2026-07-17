package com.atomikpanda.groundcontrol.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlin.math.roundToInt

/** A workspace's resolved visual identity: a solid badge [color] + a one-glyph [glyph] label. */
data class WorkspaceIdentity(val color: Color, val glyph: String)

/**
 * Curated, theme-independent badge palette. Every swatch is a mid-dark saturated tone whose
 * relative luminance keeps the white [onColor] glyph clearing WCAG 3:1 (enforced by
 * WorkspaceIdentityContrastTest), and each reads as a colored chip on BOTH the near-white light
 * surface and the near-black dark surface. APPEND new colors at the END — never reorder — because
 * the persisted-free auto color is `hash(name) % size`, so reordering would remap every workspace.
 */
object WorkspacePalette {
    val swatches: List<Color> = listOf(
        Color(0xFFD32F2F), // red 700
        Color(0xFFC2185B), // pink 700
        Color(0xFF7B1FA2), // purple 700
        Color(0xFF512DA8), // deep purple 700
        Color(0xFF303F9F), // indigo 700
        Color(0xFF1976D2), // blue 700
        Color(0xFF00838F), // cyan 800
        Color(0xFF00796B), // teal 700
        Color(0xFF388E3C), // green 700
        Color(0xFF558B2F), // light green 800
        Color(0xFFE65100), // orange 900
        Color(0xFF455A64), // blue grey 700
    )
    val onColor: Color = Color.White
}

/** Stable hue: same name → same swatch across app restarts (JVM String.hashCode is spec-stable). */
fun autoColor(name: String): Color {
    val key = name.trim()
    if (key.isEmpty()) return WorkspacePalette.swatches.first()
    val idx = (key.hashCode() and Int.MAX_VALUE) % WorkspacePalette.swatches.size
    return WorkspacePalette.swatches[idx]
}

/** Default glyph = first non-blank char, uppercased; blank name → "?". */
fun autoGlyph(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

fun autoIdentity(name: String): WorkspaceIdentity = WorkspaceIdentity(autoColor(name), autoGlyph(name))

/** Parse "#RRGGBB" or "#AARRGGBB" (with or without '#'). Returns null on malformed input. */
fun colorFromHex(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    return when (h.length) {
        6 -> h.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        8 -> h.toLongOrNull(16)?.let { Color(it) }
        else -> null
    }
}

/** Encode as "#AARRGGBB" for DataStore persistence. */
fun Color.toHex(): String {
    fun c(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(c(alpha), c(red), c(green), c(blue))
}

/** Resolve a connection's identity: override-or-auto per field. A blank name falls back to the
 *  baseUrl (matching displayName conventions); an unparseable colorOverride falls back to auto. */
fun resolveIdentity(conn: WorkspaceConnection): WorkspaceIdentity {
    val name = conn.workspaceName.ifBlank { conn.baseUrl }
    val color = conn.colorOverride?.let(::colorFromHex) ?: autoColor(name)
    val glyph = conn.glyphOverride?.trim()?.takeIf { it.isNotEmpty() } ?: autoGlyph(name)
    return WorkspaceIdentity(color, glyph)
}

/** Default (id, name) → identity resolver: auto-by-name. Overridden app-wide by a connections-aware
 *  resolver in GroundControlApp so overrides show at every badge site. */
fun defaultIdentityResolver(connectionId: String, name: String): WorkspaceIdentity = autoIdentity(name)

/** Read at any badge site: `LocalWorkspaceIdentityResolver.current(connectionId, fallbackName)`. */
val LocalWorkspaceIdentityResolver =
    staticCompositionLocalOf<(String, String) -> WorkspaceIdentity> { ::defaultIdentityResolver }
