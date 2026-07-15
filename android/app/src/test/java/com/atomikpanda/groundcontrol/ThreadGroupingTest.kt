package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.messages.OTHER_GROUP_TITLE
import com.atomikpanda.groundcontrol.ui.messages.groupThreadsByWorkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadGroupingTest {
    private fun thread(
        id: String,
        updatedAt: String,
        workItemId: String? = null,
        awaitingReply: Boolean = false,
        awaitingAgentEvent: Boolean = false,
        needsYou: Boolean = false,
        needsDecision: Boolean = false,
    ) = ThreadSummary(
        id = id, subject = id, updatedAt = updatedAt, workItemId = workItemId,
        awaitingReply = awaitingReply, awaitingAgentEvent = awaitingAgentEvent,
        needsYou = needsYou, needsDecision = needsDecision,
    )

    private fun item(id: String, title: String, kind: String) =
        WorkItemSummary(id = id, kind = kind, title = title, phase = "in_flight")

    @Test fun buckets_threads_by_work_item_with_title_and_kind_and_newest_first() {
        val threads = listOf(
            thread("t1", "2026-07-10T10:00:00Z", workItemId = "wi-1"),
            thread("t2", "2026-07-10T11:00:00Z", workItemId = "wi-1"),
        )
        val groups = groupThreadsByWorkItem(threads, listOf(item("wi-1", "Ship groups", "feature")))
        assertEquals(1, groups.size)
        assertEquals("wi-1", groups[0].workItemId)
        assertEquals("Ship groups", groups[0].title)
        assertEquals("feature", groups[0].kind)
        assertEquals(listOf("t2", "t1"), groups[0].threads.map { it.id })  // newest-first
    }

    @Test fun null_or_unmatched_work_item_collapses_into_single_other_group() {
        val threads = listOf(
            thread("t1", "2026-07-10T10:00:00Z", workItemId = null),
            thread("t2", "2026-07-10T09:00:00Z", workItemId = "wi-gone"),  // no such item
        )
        val groups = groupThreadsByWorkItem(threads, emptyList())
        assertEquals(1, groups.size)
        assertEquals(null, groups[0].workItemId)
        assertEquals(OTHER_GROUP_TITLE, groups[0].title)
        assertEquals(setOf("t1", "t2"), groups[0].threads.map { it.id }.toSet())
    }

    @Test fun groups_order_by_their_newest_thread_including_other() {
        val threads = listOf(
            thread("a1", "2026-07-10T08:00:00Z", workItemId = "wi-a"),
            thread("b1", "2026-07-10T12:00:00Z", workItemId = "wi-b"),
            thread("o1", "2026-07-10T10:00:00Z", workItemId = null),
        )
        val items = listOf(item("wi-a", "A", "feature"), item("wi-b", "B", "bug"))
        val groups = groupThreadsByWorkItem(threads, items)
        // wi-b (12:00) > Other (10:00) > wi-a (08:00)
        assertEquals(listOf("wi-b", null, "wi-a"), groups.map { it.workItemId })
    }

    @Test fun attention_rolls_up_from_any_thread_needing_the_operator() {
        val items = listOf(item("wi-q", "Quiet", "chore"), item("wi-n", "Needs", "feature"))
        val threads = listOf(
            thread("q1", "2026-07-10T10:00:00Z", workItemId = "wi-q"),
            thread("n1", "2026-07-10T09:00:00Z", workItemId = "wi-n", needsYou = true),
        )
        val byId = groupThreadsByWorkItem(threads, items).associateBy { it.workItemId }
        assertFalse(byId["wi-q"]!!.hasAttention)
        assertTrue(byId["wi-n"]!!.hasAttention)
    }

    @Test fun attention_also_rolls_up_an_unhandled_agent_event() {
        val items = listOf(item("wi-1", "X", "feature"))
        val threads = listOf(thread("t1", "2026-07-10T10:00:00Z", workItemId = "wi-1", awaitingAgentEvent = true))
        assertTrue(groupThreadsByWorkItem(threads, items)[0].hasAttention)
    }
}
