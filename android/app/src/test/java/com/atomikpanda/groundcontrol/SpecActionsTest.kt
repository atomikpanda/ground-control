package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecAction
import com.atomikpanda.groundcontrol.data.availableActions
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.isReviewInteractive
import com.atomikpanda.groundcontrol.data.parseApproveBlockers
import com.atomikpanda.groundcontrol.data.statusBanner
import com.atomikpanda.groundcontrol.data.summaryOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecActionsTest {
    private val allStatuses = listOf(
        "captured", "drafting", "needs_review", "needs_clarification",
        "approved", "dispatched", "implemented", "archived",
    )

    @Test fun available_actions_is_exhaustive_over_all_8_statuses() {
        assertEquals(
            setOf(SpecAction.REQUEST_CHANGES, SpecAction.APPROVE, SpecAction.APPROVE_ANYWAY),
            availableActions("needs_review"),
        )
        assertEquals(setOf(SpecAction.REQUEST_CHANGES, SpecAction.DISPATCH), availableActions("approved"))
        // the remaining 6 are read-only (no actions)
        listOf("captured", "drafting", "needs_clarification", "dispatched", "implemented", "archived")
            .forEach { assertTrue("$it should have no actions", availableActions(it).isEmpty()) }
        // every known status is covered above
        assertEquals(8, allStatuses.size)
    }

    @Test fun review_is_interactive_only_in_needs_review() {
        assertTrue(isReviewInteractive("needs_review"))
        listOf("approved", "dispatched", "needs_clarification", "drafting", "captured", "implemented", "archived")
            .forEach { assertEquals("$it not interactive", false, isReviewInteractive(it)) }
    }

    @Test fun status_banner_null_when_actionable_else_describes_status() {
        assertNull(statusBanner("needs_review", null))
        assertNull(statusBanner("approved", null))
        assertEquals("dispatched · task t1", statusBanner("dispatched", "t1"))
        assertEquals("dispatched", statusBanner("dispatched", null))
        assertTrue(statusBanner("needs_clarification", null)!!.contains("re-draft"))
    }

    @Test fun summary_counts_verdicts_and_unanswered_questions() {
        val crit = listOf(
            ReviewCriterion("ac1", "", "approved"),
            ReviewCriterion("ac2", "", "approved"),
            ReviewCriterion("ac3", "", "flagged"),
            ReviewCriterion("ac4", "", "unreviewed"),
        )
        val qs = listOf(ReviewQuestion("q1", "", "a"), ReviewQuestion("q2", "", null))
        val s = summaryOf(crit, qs)
        assertEquals(4, s.criteriaTotal)
        assertEquals(2, s.approved)
        assertEquals(1, s.flagged)
        assertEquals(1, s.unreviewed)
        assertEquals(1, s.unansweredQuestions)
    }

    @Test fun parse_blockers_splits_fastapi_detail() {
        val detail = "cannot approve: acceptance criteria not approved: ac2; open questions unanswered: q1"
        assertEquals(
            listOf("acceptance criteria not approved: ac2", "open questions unanswered: q1"),
            parseApproveBlockers(detail),
        )
        // tolerates detail without the prefix
        assertEquals(listOf("something"), parseApproveBlockers("something"))
    }
}
