package com.atomikpanda.groundcontrol

import androidx.compose.ui.graphics.Color
import com.atomikpanda.groundcontrol.ui.theme.WorkspacePalette
import com.atomikpanda.groundcontrol.ui.theme.autoColor
import com.atomikpanda.groundcontrol.ui.theme.autoGlyph
import com.atomikpanda.groundcontrol.ui.theme.autoIdentity
import com.atomikpanda.groundcontrol.ui.theme.colorFromHex
import com.atomikpanda.groundcontrol.ui.theme.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIdentityTest {
    private val names = listOf("acme", "Beta Corp", "gamma", "delta-ops", "Épsilon", "zeta", "eta labs")

    @Test fun auto_color_is_deterministic_and_stable() {
        for (n in names) assertEquals(autoColor(n), autoColor(n))
    }

    @Test fun auto_color_is_always_within_the_curated_palette() {
        for (n in names) assertTrue(WorkspacePalette.swatches.contains(autoColor(n)))
    }

    @Test fun palette_is_a_reasonably_large_curated_fixed_set() {
        assertTrue("palette too small to disambiguate", WorkspacePalette.swatches.size >= 10)
        assertEquals("swatches must be unique", WorkspacePalette.swatches.toSet().size, WorkspacePalette.swatches.size)
    }

    @Test fun auto_color_distributes_across_the_palette() {
        val big = (0 until 200).map { "workspace-$it" }
        assertTrue("hash collapses to one hue", big.map { autoColor(it) }.toSet().size >= 5)
    }

    @Test fun default_glyph_is_uppercased_first_letter() {
        assertEquals("A", autoGlyph("acme"))
        assertEquals("B", autoGlyph("Beta Corp"))
    }

    @Test fun glyph_falls_back_for_blank_names() {
        assertEquals("?", autoGlyph(""))
        assertEquals("?", autoGlyph("   "))
    }

    @Test fun auto_identity_bundles_color_and_glyph() {
        val id = autoIdentity("acme")
        assertEquals(autoColor("acme"), id.color)
        assertEquals("A", id.glyph)
    }

    @Test fun hex_codec_round_trips() {
        val c = Color(0xFF1976D2)
        assertEquals(c, colorFromHex(c.toHex()))
        assertEquals(Color(0xFFD32F2F), colorFromHex("#D32F2F"))   // 6-digit → opaque
        assertNotNull(colorFromHex("#FF00796B"))
        assertNull(colorFromHex("nope"))
        assertNull(colorFromHex("#12"))
    }
}
