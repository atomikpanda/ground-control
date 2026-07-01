package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkItemDtosTest {
    private val json = buildJson()

    @Test
    fun parses_full_item_with_attention() {
        val w = json.decodeFromString(
            WorkItemSummary.serializer(),
            """{"id":"wi-1","kind":"feature","title":"Make capture conversational","phase":"in_flight",
                "spec_id":"s1","task_slugs":["a","b"],"thread_ids":["t1"],
                "attention":{"needs_approval":false,"blocked":true,"blocked_tasks":1,"total_tasks":3},
                "updated_at":"2026-07-01T00:00:00+00:00","extra_unknown":1}""",
        )
        assertEquals("wi-1", w.id)
        assertEquals("in_flight", w.phase)
        assertEquals("s1", w.specId)
        assertEquals(listOf("a", "b"), w.taskSlugs)
        assertTrue(w.attention.blocked)
        assertEquals(1, w.attention.blockedTasks)
        assertEquals(3, w.attention.totalTasks)
    }

    @Test
    fun defaults_when_fields_omitted() {
        val w = json.decodeFromString(
            WorkItemSummary.serializer(),
            """{"id":"wi-2","kind":"question","title":"?","phase":"inbox"}""",
        )
        assertEquals(null, w.specId)
        assertTrue(w.taskSlugs.isEmpty() && w.threadIds.isEmpty())
        assertEquals(false, w.attention.needsDecision)
        assertEquals(0, w.attention.totalTasks)
    }
}
