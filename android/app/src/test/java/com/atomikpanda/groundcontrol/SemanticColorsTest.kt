package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.home.UrgencyTier
import com.atomikpanda.groundcontrol.ui.theme.SemanticDark
import com.atomikpanda.groundcontrol.ui.theme.SemanticLight
import com.atomikpanda.groundcontrol.ui.theme.accentFor
import com.atomikpanda.groundcontrol.ui.theme.chipHue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticColorsTest {
    @Test fun accent_maps_each_kind_dark() {
        assertEquals(SemanticDark.blocker, accentFor(UrgencyTier.BLOCKER, SemanticDark))
        assertEquals(SemanticDark.question, accentFor(UrgencyTier.QUESTION, SemanticDark))
        assertEquals(SemanticDark.approval, accentFor(UrgencyTier.APPROVAL, SemanticDark))
    }

    @Test fun accent_maps_each_kind_light() {
        assertEquals(SemanticLight.blocker, accentFor(UrgencyTier.BLOCKER, SemanticLight))
        assertEquals(SemanticLight.question, accentFor(UrgencyTier.QUESTION, SemanticLight))
        assertEquals(SemanticLight.approval, accentFor(UrgencyTier.APPROVAL, SemanticLight))
    }

    @Test fun roles_differ_between_dark_and_light() {
        assertNotEquals(SemanticDark.approval, SemanticLight.approval)
        assertNotEquals(SemanticDark.blocker, SemanticLight.blocker)
        assertNotEquals(SemanticDark.error, SemanticLight.error)
    }

    @Test fun chip_hue_is_stable_and_in_palette() {
        assertEquals(chipHue("conn-a", SemanticDark), chipHue("conn-a", SemanticDark))
        assertTrue(SemanticDark.chipHues.contains(chipHue("conn-a", SemanticDark)))
        // distinct ids generally land on different hues (not guaranteed, but the set is used)
        assertTrue(SemanticDark.chipHues.size >= 4)
    }
}
