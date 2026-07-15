package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.Summary
import com.atomikpanda.groundcontrol.ui.specdetail.ChipRole
import com.atomikpanda.groundcontrol.ui.specdetail.readinessChips
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessTest {
    @Test fun builds_three_chips_from_summary() {
        val chips = readinessChips(
            Summary(criteriaTotal = 5, approved = 2, flagged = 1, unreviewed = 2, unansweredQuestions = 3),
        )
        assertEquals(
            listOf(
                "2/5 approved" to ChipRole.APPROVED,
                "1 flagged" to ChipRole.FLAGGED,
                "3 unanswered" to ChipRole.UNANSWERED,
            ),
            chips.map { it.label to it.role },
        )
    }

    @Test fun zero_values_still_render_all_three_chips() {
        val chips = readinessChips(
            Summary(criteriaTotal = 0, approved = 0, flagged = 0, unreviewed = 0, unansweredQuestions = 0),
        )
        assertEquals(listOf("0/0 approved", "0 flagged", "0 unanswered"), chips.map { it.label })
        assertEquals(listOf(ChipRole.APPROVED, ChipRole.FLAGGED, ChipRole.UNANSWERED), chips.map { it.role })
    }
}
