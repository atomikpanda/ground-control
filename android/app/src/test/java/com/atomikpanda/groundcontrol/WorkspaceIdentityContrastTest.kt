package com.atomikpanda.groundcontrol

import androidx.compose.ui.graphics.Color
import com.atomikpanda.groundcontrol.ui.theme.Palette
import com.atomikpanda.groundcontrol.ui.theme.WorkspacePalette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The badge is a solid swatch with a white glyph; its legibility does NOT depend on the theme
 *  surface, so the guarantee is white-glyph-on-swatch contrast. 3:1 is the WCAG AA bar for large/
 *  graphical text. We also assert each swatch stands off both theme backgrounds. */
class WorkspaceIdentityContrastTest {
    private fun channel(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    private fun luminance(c: Color) =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun white_glyph_is_legible_on_every_swatch() {
        for (c in WorkspacePalette.swatches) {
            val r = contrast(WorkspacePalette.onColor, c)
            assertTrue("white glyph on $c is $r, below 3:1", r >= 3.0)
        }
    }

    @Test fun swatches_stand_off_both_theme_backgrounds() {
        for (c in WorkspacePalette.swatches) {
            assertTrue("swatch $c blends into dark bg", contrast(c, Palette.darkBackground) >= 1.5)
            assertTrue("swatch $c blends into light bg", contrast(c, Palette.lightBackground) >= 1.5)
        }
    }
}
