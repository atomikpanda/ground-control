package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
import com.atomikpanda.groundcontrol.ui.home.UrgencyTier
import com.atomikpanda.groundcontrol.ui.home.approvalsFrom
import com.atomikpanda.groundcontrol.ui.home.blockersFrom
import com.atomikpanda.groundcontrol.ui.home.decisionsFrom
import com.atomikpanda.groundcontrol.ui.home.displayName
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

    @Test fun questions_only_include_threads_that_need_you() {
        val conn = WorkspaceConnection("c1", "http://h", "tok", "ws")
        val threads = listOf(
            ThreadSummary(id = "t1", subject = "needs you", needsYou = true),
            ThreadSummary(id = "t2", subject = "awaiting agent", awaitingReply = true),  // not surfaced
            ThreadSummary(id = "t3", subject = "plain unread", unseen = true),            // not an action card
        )
        val items = questionsFrom(conn, threads)
        assertEquals(1, items.size)
        assertEquals("t1", (items[0] as NeedsYouItem.Question).threadId)
    }

    @Test fun decisions_only_include_threads_that_need_decision() {
        val conn = WorkspaceConnection("c1", "http://h", "tok", "ws")
        val threads = listOf(
            ThreadSummary(id = "t1", subject = "pick an option", needsDecision = true),
            ThreadSummary(id = "t2", subject = "awaiting agent", awaitingReply = true),  // not surfaced
            ThreadSummary(id = "t3", subject = "plain unread", unseen = true),            // not an action card
        )
        val items = decisionsFrom(conn, threads)
        assertEquals(1, items.size)
        assertEquals("t1", (items[0] as NeedsYouItem.Question).threadId)
    }

    @Test fun decisions_do_not_duplicate_a_thread_that_also_needs_you() {
        val conn = WorkspaceConnection("c1", "http://h", "tok", "ws")
        // A thread flagged both needsYou and needsDecision should surface once, not twice.
        val threads = listOf(
            ThreadSummary(id = "t1", subject = "both", needsYou = true, needsDecision = true),
        )
        assertEquals(1, questionsFrom(conn, threads).size)
        assertEquals(0, decisionsFrom(conn, threads).size)
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

    @Test fun displayName_falls_back_to_baseUrl_when_name_blank() {
        val blank = WorkspaceConnection("c", "http://host:47100", null, "")
        assertEquals("http://host:47100", blank.displayName())
    }

    @Test fun sort_merges_two_connections_and_preserves_connectionId() {
        // Two workspaces, items across all three tiers. Expected order is tier-first
        // (blocker, question, approval), newest-first within a tier; identity must survive.
        val blockerA = NeedsYouItem.Blocker("connA", "wsA", "k1", "r", "2026-06-24T09:00:00Z")
        val questionB = NeedsYouItem.Question("connB", "wsB", "t1", "Q", "m", "2026-06-24T12:00:00Z")
        val questionA = NeedsYouItem.Question("connA", "wsA", "t2", "Q", "m", "2026-06-24T08:00:00Z")
        val approvalB = NeedsYouItem.Approval("connB", "wsB", "s1", "Title")

        val sorted = sortNeedsYou(listOf(approvalB, questionA, blockerA, questionB))

        assertEquals(listOf(blockerA, questionB, questionA, approvalB), sorted)
        assertEquals(listOf("connA", "connB", "connA", "connB"), sorted.map { it.connectionId })
    }
}
