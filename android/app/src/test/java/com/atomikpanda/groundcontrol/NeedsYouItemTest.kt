// android/app/src/test/java/com/atomikpanda/groundcontrol/NeedsYouItemTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
import com.atomikpanda.groundcontrol.ui.home.UrgencyTier
import com.atomikpanda.groundcontrol.ui.home.approvalsFrom
import com.atomikpanda.groundcontrol.ui.home.blockersFrom
import com.atomikpanda.groundcontrol.ui.home.questionsFrom
import com.atomikpanda.groundcontrol.ui.home.sortNeedsYou
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsYouItemTest {
    private val conn = WorkspaceConnection("c1", "http://h:47100", null, "ws-a")

    @Test fun approvals_only_include_needs_review_specs() {
        val specs = listOf(
            SpecSummary("s1", "Alpha", "needs_review"),
            SpecSummary("s2", "Beta", "approved"),
            SpecSummary("s3", "Gamma", "drafting"),
        )
        val items = approvalsFrom(conn, specs)
        assertEquals(listOf("s1"), items.map { (it as NeedsYouItem.Approval).specId })
        assertEquals("ws-a", items[0].workspaceName)
        assertEquals(UrgencyTier.APPROVAL, items[0].tier)
    }

    @Test fun questions_only_include_threads_awaiting_reply() {
        val threads = listOf(
            ThreadSummary("t1", subject = "Q", awaitingReply = true),
            ThreadSummary("t2", subject = "Done", awaitingReply = false),
        )
        val items = questionsFrom(conn, threads)
        assertEquals(listOf("t1"), items.map { (it as NeedsYouItem.Question).threadId })
        assertEquals(UrgencyTier.QUESTION, items[0].tier)
    }

    @Test fun blockers_only_include_blocked_tasks() {
        val tasks = listOf(
            TaskSummary(slug = "k1", phase = "dev", branch = "b", blockedReason = "needs key"),
            TaskSummary(slug = "k2", phase = "dev", branch = "b", blockedReason = null),
        )
        val items = blockersFrom(conn, tasks)
        assertEquals(listOf("k1"), items.map { (it as NeedsYouItem.Blocker).taskSlug })
        assertEquals(UrgencyTier.BLOCKER, items[0].tier)
    }

    @Test fun sort_orders_blocker_then_question_then_approval() {
        val a = NeedsYouItem.Approval("c1", "ws", "s1", "Title")
        val q = NeedsYouItem.Question("c1", "ws", "t1", "Q", "msg", "2026-06-24T10:00:00Z")
        val b = NeedsYouItem.Blocker("c1", "ws", "k1", "r", "2026-06-24T09:00:00Z")
        assertEquals(listOf(b, q, a), sortNeedsYou(listOf(a, q, b)))
    }

    @Test fun sort_is_newest_first_within_a_tier() {
        val older = NeedsYouItem.Question("c1", "ws", "old", "Q", "m", "2026-06-24T08:00:00Z")
        val newer = NeedsYouItem.Question("c1", "ws", "new", "Q", "m", "2026-06-24T12:00:00Z")
        assertEquals(listOf(newer, older), sortNeedsYou(listOf(older, newer)))
    }
}
