package com.atomikpanda.groundcontrol.data

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

class PlanAssumptionsApiTest {
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { mshipDefaults() }

    private val conn = WorkspaceConnection("1", "http://host:47100", "secret", "ws")

    private val envelopeJson = """
        {"task":"t1","fresh":true,"pending":1,"flags":[
          {"axis":"scope","source":"agent","reason":"broad scope","axis_fingerprint":"abc123",
           "approved":false,"approved_by":null,"approved_reason":null}
        ]}
    """.trimIndent()

    @Test fun get_plan_assumptions_hits_path_with_bearer() = runTest {
        var seenAuth: String? = null
        var seenUrl: String? = null
        val api = SpecApi(client { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            seenUrl = req.url.toString()
            respond(envelopeJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val envelope = api.getPlanAssumptions(conn, "t1")
        assertEquals("t1", envelope.task)
        assertTrue(envelope.fresh)
        assertEquals(1, envelope.pending)
        assertEquals(1, envelope.flags.size)
        assertEquals("scope", envelope.flags[0].axis)
        assertEquals("abc123", envelope.flags[0].axisFingerprint)
        assertEquals("Bearer secret", seenAuth)
        assertTrue(seenUrl!!.endsWith("/plan-assumptions/t1"))
    }

    @Test fun approve_plan_flag_posts_axis_and_reason() = runTest {
        var url: String? = null
        var method: String? = null
        var body: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString()
            method = req.method.value
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(envelopeJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val envelope = api.approvePlanFlag(conn, "t1", "scope", "looks fine")
        assertTrue(url!!.endsWith("/plan-assumptions/t1/approve"))
        assertEquals("POST", method)
        assertTrue(body!!.contains("\"axis\":\"scope\""))
        assertTrue(body!!.contains("\"reason\":\"looks fine\""))
        assertEquals("t1", envelope.task)
    }
}
