package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.home.needsYouHeader
import com.atomikpanda.groundcontrol.ui.home.reviewInQueueCta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStringsTest {
    @Test fun header_shows_count_when_positive() {
        assertEquals("Needs you · 3", needsYouHeader(3))
        assertEquals("Needs you · 1", needsYouHeader(1))
    }

    @Test fun header_is_caughtup_label_when_zero() {
        // Zero is a distinct caught-up state, never "Needs you · 0".
        assertEquals("You're all caught up", needsYouHeader(0))
    }

    @Test fun cta_reflects_count_and_is_null_when_zero() {
        assertEquals("Review 3 in Queue", reviewInQueueCta(3))
        assertEquals("Review 1 in Queue", reviewInQueueCta(1))
        assertNull(reviewInQueueCta(0))   // no funnel action in the caught-up state
    }
}
