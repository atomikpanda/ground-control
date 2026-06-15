package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.HealthResponse
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_spec_summary_with_snake_case_fields() {
        val s = json.decodeFromString<SpecSummary>(
            """{"id":"dq","title":"Decision queue","status":"needs_review",
                "task_slug":"dq","affected_repos":["mothership","ground-control"]}"""
        )
        assertEquals("dq", s.id)
        assertEquals("Decision queue", s.title)
        assertEquals("needs_review", s.status)
        assertEquals(listOf("mothership", "ground-control"), s.affectedRepos)
    }

    @Test fun spec_summary_tolerates_missing_repos_and_unknown_keys() {
        val s = json.decodeFromString<SpecSummary>(
            """{"id":"x","title":"X","status":"drafting","task_slug":null,"extra":42}"""
        )
        assertEquals(emptyList<String>(), s.affectedRepos)   // default
        assertEquals(null, s.taskSlug)
    }

    @Test fun parses_health() {
        val h = json.decodeFromString<HealthResponse>("""{"status":"ok","workspace":"mship-workspace"}""")
        assertEquals("ok", h.status)
        assertEquals("mship-workspace", h.workspace)
    }
}
