package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecDetailDtosTest {
    private val json = buildJson()

    @Test fun parses_full_spec_record_including_body_and_nested() {
        val raw = """
        {"id":"s1","title":"T","status":"needs_review","created_at":"x","updated_at":"y",
         "affected_repos":["r"],
         "acceptance_criteria":[{"id":"ac1","text":"AC one","verdict":"approved"},
                                {"id":"ac2","text":"AC two","verdict":"unreviewed"}],
         "open_questions":[{"id":"q1","text":"Q one?","answer":"yes"},
                           {"id":"q2","text":"Q two?","answer":null}],
         "non_goals":["ng"],"risks":["rk"],"task_slug":null,
         "body":"## Problem\n\nhi\n\n## Architecture\n\ncustom section"}
        """.trimIndent()
        val rec = json.decodeFromString(SpecRecord.serializer(), raw)
        assertEquals("s1", rec.id)
        assertEquals("needs_review", rec.status)
        assertTrue(rec.body.contains("## Architecture"))
        assertEquals(2, rec.acceptanceCriteria.size)
        assertEquals("approved", rec.acceptanceCriteria[0].verdict)
        assertNull(rec.openQuestions[1].answer)
        assertEquals(listOf("ng"), rec.nonGoals)
        assertNull(rec.taskSlug)
    }

    @Test fun parses_review_payload_and_ignores_unknown_context() {
        val raw = """
        {"id":"s1","status":"approved",
         "acceptance_criteria":[{"id":"ac1","text":"AC","verdict":"approved"}],
         "open_questions":[],
         "context":{"problem":"p","user_story":"u","approach":"a","non_goals":[],"risks":[],"affected_repos":[]},
         "summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}
        """.trimIndent()
        val rev = json.decodeFromString(SpecReview.serializer(), raw)
        assertEquals("approved", rev.status)
        assertEquals(1, rev.summary.criteriaTotal)
        assertEquals(1, rev.acceptanceCriteria.size)
    }

    @Test fun parses_dispatch_result() {
        val raw = """
        {"spec":{"id":"s1","title":"T","status":"dispatched"},
         "task_slug":"s1","spawned":true,"handoff":"do the thing"}
        """.trimIndent()
        val dr = json.decodeFromString(DispatchResult.serializer(), raw)
        assertEquals("s1", dr.taskSlug)
        assertTrue(dr.spawned)
        assertEquals("dispatched", dr.spec.status)
    }
}
