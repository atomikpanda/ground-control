package com.atomikpanda.groundcontrol

import androidx.compose.ui.graphics.Color
import com.atomikpanda.groundcontrol.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.1 contrast guard for the muted secondary text color, which carries a lot of real
 *  content (card meta, timestamps, read-state). Muted-on-surface must clear the AA 4.5:1 minimum. */
class ThemeContrastTest {
    private fun channel(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun muted_text_meets_wcag_aa_on_dark_surface() {
        val ratio = contrast(Palette.darkMuted, Palette.darkSurface)
        assertTrue("darkMuted on darkSurface is $ratio, below WCAG AA 4.5:1", ratio >= 4.5)
    }
}
