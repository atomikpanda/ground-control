package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.Summary
import com.atomikpanda.groundcontrol.ui.specdetail.ChipRole
import com.atomikpanda.groundcontrol.ui.specdetail.readinessChips
import com.atomikpanda.groundcontrol.ui.specdetail.soleBlockerApproveLabel
import com.atomikpanda.groundcontrol.ui.specdetail.unansweredLeadText
import com.atomikpanda.groundcontrol.ui.specdetail.unansweredQuestionsLead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun unanswered_lead_text_formats_count() {
        assertEquals("2 unanswered question(s) — answer to approve", unansweredLeadText(2))
        assertEquals("1 unanswered question(s) — answer to approve", unansweredLeadText(1))
    }

    @Test fun lead_shows_only_in_review_with_unanswered_questions() {
        val sum = Summary(criteriaTotal = 3, approved = 3, flagged = 0, unreviewed = 0, unansweredQuestions = 2)
        assertEquals("2 unanswered question(s) — answer to approve", unansweredQuestionsLead("needs_review", sum))
        // ac5: no open questions → no lead
        assertNull(unansweredQuestionsLead("needs_review", sum.copy(unansweredQuestions = 0)))
        // not a review-phase spec → no lead
        assertNull(unansweredQuestionsLead("approved", sum))
    }

    @Test fun sole_blocker_label_only_when_questions_are_the_only_blocker() {
        // all criteria approved, 1 unanswered question → questions are the sole blocker
        assertEquals(
            "Answer 1 question(s) to approve",
            soleBlockerApproveLabel("needs_review", Summary(2, 2, 0, 0, 1)),
        )
        // a flagged criterion also blocks → not sole → null
        assertNull(soleBlockerApproveLabel("needs_review", Summary(2, 1, 1, 0, 1)))
        // an unreviewed criterion also blocks → not sole → null
        assertNull(soleBlockerApproveLabel("needs_review", Summary(2, 1, 0, 1, 1)))
        // no unanswered questions → nothing to guide toward → null
        assertNull(soleBlockerApproveLabel("needs_review", Summary(2, 2, 0, 0, 0)))
        // not review-phase → null
        assertNull(soleBlockerApproveLabel("approved", Summary(2, 2, 0, 0, 1)))
    }
}
