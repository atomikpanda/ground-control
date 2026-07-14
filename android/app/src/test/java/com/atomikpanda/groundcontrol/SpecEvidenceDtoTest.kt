package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AC-evidence loop: serve's build_review / GET /specs/{id} attach per-criterion evidence
 *  ({kind, ref, note}) and a summary `unverified` count. These assert the GC DTOs deserialize both,
 *  and stay backward-compatible with specs that carry neither. */
class SpecEvidenceDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun criterion_parses_evidence_entries() {
        val c = json.decodeFromString<ReviewCriterion>(
            """{"id":"ac1","text":"t","verdict":"approved",
                "evidence":[{"kind":"test","ref":"pytest -q","note":"18 passed"},
                            {"kind":"commit","ref":"abc123","note":null}]}"""
        )
        assertEquals(2, c.evidence.size)
        assertEquals("test", c.evidence[0].kind)
        assertEquals("pytest -q", c.evidence[0].ref)
        assertEquals("18 passed", c.evidence[0].note)
        assertNull(c.evidence[1].note)
    }

    @Test fun criterion_without_evidence_defaults_empty() {
        val c = json.decodeFromString<ReviewCriterion>("""{"id":"ac1","text":"t","verdict":"unreviewed"}""")
        assertTrue(c.evidence.isEmpty())
    }

    @Test fun summary_parses_unverified_and_defaults_to_zero() {
        assertEquals(2, json.decodeFromString<ReviewSummary>("""{"criteria_total":3,"unverified":2}""").unverified)
        assertEquals(0, json.decodeFromString<ReviewSummary>("""{"criteria_total":3}""").unverified)
    }
}
