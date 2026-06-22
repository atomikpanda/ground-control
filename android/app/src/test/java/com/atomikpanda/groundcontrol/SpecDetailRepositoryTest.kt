package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpecDetailRepositoryTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun load_returns_full_record() = runTest {
        val repo = SpecDetailRepository(SpecApi(HttpClient(MockEngine {
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"## Problem\n\nx"}""",
                HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() }))
        val rec = repo.load(conn, "s1")
        assertEquals("s1", rec.id)
        assertEquals("## Problem\n\nx", rec.body)
    }

    @Test fun set_verdict_returns_review() = runTest {
        val repo = SpecDetailRepository(SpecApi(HttpClient(MockEngine {
            respond("""{"id":"s1","status":"needs_review","acceptance_criteria":[{"id":"ac1","text":"t","verdict":"approved"}],
                       "open_questions":[],"summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() }))
        val rev = repo.setVerdict(conn, "s1", "ac1", "approved")
        assertEquals(1, rev.summary.approved)
    }
}
