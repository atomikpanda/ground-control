package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecApiTest {
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(buildJson()) } }

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
}
