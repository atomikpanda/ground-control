package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDtosTest {
    private val json = buildJson()

    @Test fun parses_task_summary_with_maps_and_unknown_keys() {
        val raw = """
        {"slug":"t1","description":"Do it","phase":"dev","branch":"feat/t1",
         "affected_repos":["mothership"],"pr_urls":{"mothership":"https://x/pull/1"},
         "test_results":{"mothership":"pass"},"blocked_reason":null,"depends_on":["up"],
         "spec_count":1,"orphan":false,"tests_failing":false,
         "finished_at":null,"created_at":"2026-06-22T00:00:00Z","worktrees":{"mothership":"/x"}}
        """.trimIndent()
        val t = json.decodeFromString(TaskSummary.serializer(), raw)
        assertEquals("t1", t.slug)
        assertEquals("Do it", t.description)
        assertEquals("dev", t.phase)
        assertEquals("https://x/pull/1", t.prUrls["mothership"])
        assertEquals("pass", t.testResults["mothership"])
        assertEquals(listOf("up"), t.dependsOn)
        assertNull(t.finishedAt)
    }

    @Test fun parses_journal_entry_with_optional_fields() {
        val raw = """{"timestamp":"2026-06-22T00:00:00Z","message":"ran tests","repo":"mothership",
                     "action":"ran tests","test_state":"pass","open_question":null,"iteration":2}"""
        val e = json.decodeFromString(JournalEntry.serializer(), raw.trimIndent())
        assertEquals("ran tests", e.message)
        assertEquals("pass", e.testState)
        assertEquals(2, e.iteration)
        assertNull(e.openQuestion)
    }
}
