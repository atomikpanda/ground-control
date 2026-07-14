// app/src/test/java/com/atomikpanda/groundcontrol/QueueV2ApiTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 9: the Queue v2 write client — prose-verdict + flag-with-comment. */
class QueueV2ApiTest {
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { mshipDefaults() }

    private val conn = WorkspaceConnection("1", "http://host:47100", "secret", "ws")

    private val reviewJson = """
        {"id":"s1","status":"needs_review",
         "acceptance_criteria":[{"id":"ac1","text":"t","verdict":"flagged","comment":"fix"}],
         "open_questions":[],
         "prose_verdicts":{"approach":{"verdict":"flagged","comment":"unclear"}},
         "summary":{"criteria_total":1,"approved":0,"flagged":1,"unreviewed":0,"open_questions_unanswered":0}}
    """.trimIndent()

    @Test fun set_prose_verdict_posts_path_body_and_bearer() = runTest {
        var url: String? = null
        var method: String? = null
        var body: String? = null
        var auth: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString(); method = req.method.value; auth = req.headers[HttpHeaders.Authorization]
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rev = api.setProseVerdict(conn, "s1", "approach", "flagged", "unclear")
        assertEquals("POST", method)
        assertTrue(url!!.endsWith("/specs/s1/prose-verdict"))
        assertEquals("Bearer secret", auth)
        assertTrue(body!!.contains("\"section_id\":\"approach\""))
        assertTrue(body!!.contains("\"verdict\":\"flagged\""))
        assertTrue(body!!.contains("\"comment\":\"unclear\""))
        // the review round-trips the new prose_verdicts + criterion comment fields
        assertEquals("flagged", rev.proseVerdicts["approach"]!!.verdict)
        assertEquals("fix", rev.acceptanceCriteria[0].comment)
    }

    @Test fun set_prose_verdict_omits_comment_when_null() = runTest {
        var body: String? = null
        val api = SpecApi(client { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.setProseVerdict(conn, "s1", "approach", "approved")
        // encodeDefaults=false (buildJson) omits the null comment — no "comment":null noise
        assertTrue(!body!!.contains("comment"))
    }

    @Test fun set_verdict_sends_the_flag_comment() = runTest {
        var body: String? = null
        val api = SpecApi(client { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.setVerdict(conn, "s1", "ac1", "flagged", "fix")
        assertTrue(body!!.contains("\"criterion_id\":\"ac1\""))
        assertTrue(body!!.contains("\"verdict\":\"flagged\""))
        assertTrue(body!!.contains("\"comment\":\"fix\""))
    }
}
