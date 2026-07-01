package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkItemApiTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(ContentType, "application/json")

    @Test
    fun get_item_path_auth_parse() = runTest {
        var url: String? = null
        val api = SpecApi(HttpClient(MockEngine { req ->
            url = req.url.toString()
            respond(
                """{"id":"wi-1","kind":"feature","title":"T","phase":"in_flight",
                    "task_slugs":["a"],"thread_ids":["t1"]}""",
                HttpStatusCode.OK, jsonHdr,
            )
        }) { mshipDefaults() })
        val wi = api.getItem(conn, "wi-1")
        assertEquals("in_flight", wi.phase)
        assertTrue(url!!.endsWith("/items/wi-1"))
    }

    @Test
    fun get_spec_review_path_and_counts() = runTest {
        var url: String? = null
        val api = SpecApi(HttpClient(MockEngine { req ->
            url = req.url.toString()
            respond(
                """{"id":"spec-1","status":"needs_review",
                    "summary":{"criteria_total":3,"approved":2,"flagged":0,"unreviewed":1,
                    "open_questions_unanswered":0}}""",
                HttpStatusCode.OK, jsonHdr,
            )
        }) { mshipDefaults() })
        val review = api.getSpecReview(conn, "spec-1")
        assertEquals(3, review.summary.criteriaTotal)
        assertEquals(2, review.summary.approved)
        assertTrue(url!!.endsWith("/specs/spec-1/review"))
    }
}
