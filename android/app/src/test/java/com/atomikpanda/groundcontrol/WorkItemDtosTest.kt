package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertTrue(w.externalLinks.isEmpty())
        assertEquals(false, w.unattended)
    }

    @Test
    fun parses_unattended_flag() {
        val w = json.decodeFromString(
            WorkItemSummary.serializer(),
            """{"id":"wi-5","kind":"feature","title":"Auto","phase":"ready","unattended":true}""",
        )
        assertTrue(w.unattended)
    }

    @Test
    fun parses_external_links() {
        val w = json.decodeFromString(
            WorkItemSummary.serializer(),
            """{"id":"wi-3","kind":"feature","title":"Links","phase":"in_flight",
                "external_links":[
                    {"provider":"github","url":"https://github.com/o/r/pull/1","title":"PR #1"},
                    {"provider":"linear","url":"https://linear.app/x/issue/MOS-201"}
                ]}""",
        )
        assertEquals(2, w.externalLinks.size)
        assertEquals("github", w.externalLinks[0].provider)
        assertEquals("https://github.com/o/r/pull/1", w.externalLinks[0].url)
        assertEquals("PR #1", w.externalLinks[0].title)
        // title defaults to empty string when the server omits it
        assertEquals("", w.externalLinks[1].title)
    }

    @Test
    fun null_external_links_coerces_to_empty() {
        // An explicit null (not just an absent key) must not fail the whole decode.
        val w = json.decodeFromString(
            WorkItemSummary.serializer(),
            """{"id":"wi-4","kind":"chore","title":"n","phase":"inbox","external_links":null}""",
        )
        assertTrue(w.externalLinks.isEmpty())
    }

    @Test fun parses_work_item_active_task_fields() {
        val raw = """
        {"id":"wi1","kind":"feature","title":"T","phase":"in_flight",
         "active_phase":"dev","active_last_activity_at":"2026-07-13T12:00:00Z"}
        """.trimIndent()
        val w = json.decodeFromString(WorkItemSummary.serializer(), raw)
        assertEquals("dev", w.activePhase)
        assertEquals("2026-07-13T12:00:00Z", w.activeLastActivityAt)
    }

    @Test fun work_item_active_fields_default_null() {
        val raw = """{"id":"wi1","kind":"feature","title":"T","phase":"inbox"}"""
        val w = json.decodeFromString(WorkItemSummary.serializer(), raw)
        assertNull(w.activePhase)
        assertNull(w.activeLastActivityAt)
    }
}
