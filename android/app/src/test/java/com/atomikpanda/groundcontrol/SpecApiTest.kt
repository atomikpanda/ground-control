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

class SpecApiTest {
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { mshipDefaults() }

    private val conn = WorkspaceConnection("1", "http://host:47100", "secret", "ws")

    @Test fun list_specs_hits_specs_path_with_bearer() = runTest {
        var seenAuth: String? = null
        var seenUrl: String? = null
        val api = SpecApi(client { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            seenUrl = req.url.toString()
            respond(
                """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":["r"]}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val specs = api.listSpecs(conn)
        assertEquals(1, specs.size)
        assertEquals("a", specs[0].id)
        assertEquals("Bearer secret", seenAuth)
        assertTrue(seenUrl!!.endsWith("/specs"))
    }

    @Test fun health_returns_workspace_name() = runTest {
        val api = SpecApi(client { respond(
            """{"status":"ok","workspace":"mship-workspace"}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        assertEquals("mship-workspace", api.health(conn).workspace)
    }

    @Test(expected = Exception::class)
    fun list_specs_throws_on_401() = runTest {
        val api = SpecApi(client { respond("nope", HttpStatusCode.Unauthorized) })
        api.listSpecs(conn)
    }

    private val reviewJson = """
        {"id":"s1","status":"approved","acceptance_criteria":[{"id":"ac1","text":"t","verdict":"approved"}],
         "open_questions":[],"summary":{"criteria_total":1,"approved":1,"flagged":0,"unreviewed":0,"open_questions_unanswered":0}}
    """.trimIndent()

    @Test fun get_spec_hits_id_path() = runTest {
        var url: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString()
            respond("""{"id":"s1","title":"T","status":"needs_review","body":"b"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rec = api.getSpec(conn, "s1")
        assertEquals("s1", rec.id)
        assertTrue(url!!.endsWith("/specs/s1"))
    }

    @Test fun post_verdict_sends_body_and_returns_review() = runTest {
        var body: String? = null
        var method: String? = null
        val api = SpecApi(client { req ->
            method = req.method.value
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rev = api.setVerdict(conn, "s1", "ac1", "approved")
        assertEquals("POST", method)
        assertTrue(body!!.contains("\"criterion_id\":\"ac1\""))
        assertTrue(body!!.contains("\"verdict\":\"approved\""))
        assertEquals(1, rev.summary.approved)
    }

    @Test fun approve_sends_bypass_flag() = runTest {
        var body: String? = null
        val api = SpecApi(client { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(reviewJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.approve(conn, "s1", bypassGate = true)
        assertTrue(body!!.contains("\"bypass_gate\":true"))
    }

    @Test fun dispatch_returns_result() = runTest {
        val api = SpecApi(client {
            respond("""{"spec":{"id":"s1","title":"T","status":"dispatched"},"task_slug":"s1","spawned":true,"handoff":"go"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val dr = api.dispatch(conn, "s1")
        assertEquals("s1", dr.taskSlug)
        assertTrue(dr.spawned)
    }

    @Test(expected = com.atomikpanda.groundcontrol.data.NotFoundException::class)
    fun get_spec_maps_404() = runTest {
        val api = SpecApi(client { respond("""{"detail":"no spec"}""", HttpStatusCode.NotFound,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        api.getSpec(conn, "missing")
    }

    @Test fun approve_409_maps_to_conflict_with_detail() = runTest {
        val api = SpecApi(client { respond("""{"detail":"cannot approve: ac2; q1"}""", HttpStatusCode.Conflict,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            api.approve(conn, "s1", bypassGate = false)
            throw AssertionError("expected ApiConflictException")
        } catch (e: com.atomikpanda.groundcontrol.data.ApiConflictException) {
            assertTrue(e.detail.contains("cannot approve"))
        }
    }

    @Test fun create_spec_posts_to_specs_and_returns_record() = runTest {
        var url: String? = null
        var method: String? = null
        var body: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString(); method = req.method.value
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"my-idea","title":"My idea","status":"drafting","body":""}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rec = api.createSpec(conn, "My idea", listOf("ground-control"))
        assertEquals("my-idea", rec.id)
        assertEquals("POST", method)
        assertTrue(url!!.endsWith("/specs"))
        assertTrue(body!!.contains("\"title\":\"My idea\""))
        assertTrue(body!!.contains("\"affected_repos\":[\"ground-control\"]"))
        // encodeDefaults=false (kotlinx default, used by buildJson()) omits default/null
        // fields, so the unset id/task_slug are NOT serialized (no `"id":null` noise).
        assertTrue(!body!!.contains("\"id\""))
        assertTrue(!body!!.contains("task_slug"))
    }

    @Test fun create_spec_409_maps_to_conflict() = runTest {
        val api = SpecApi(client { respond("""{"detail":"spec 'my-idea' already exists"}""",
            HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            api.createSpec(conn, "My idea", emptyList())
            throw AssertionError("expected ApiConflictException")
        } catch (e: com.atomikpanda.groundcontrol.data.ApiConflictException) {
            assertTrue(e.detail.contains("already exists"))
        }
    }
}
