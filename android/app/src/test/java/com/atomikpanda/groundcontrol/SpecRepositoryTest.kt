package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.buildJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecRepositoryTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun apiRoutingByHost() = SpecApi(HttpClient(MockEngine { req ->
        when (req.url.host) {
            "good" -> respond(
                """[{"id":"a","title":"A","status":"approved","task_slug":null,"affected_repos":[]}]""",
                HttpStatusCode.OK, jsonHdr)
            else -> respond("boom", HttpStatusCode.InternalServerError)
        }
    }) { install(ContentNegotiation) { json(buildJson()) } })

    @Test fun aggregates_across_connections_and_isolates_failures() = runTest {
        val repo = SpecRepository(apiRoutingByHost())
        val results = repo.listAllSpecs(listOf(
            WorkspaceConnection("1", "http://good:47100", null, "ws-good"),
            WorkspaceConnection("2", "http://bad:47100", null, "ws-bad"),
        ))
        assertEquals(2, results.size)
        val good = results.first { it.connection.workspaceName == "ws-good" }
        val bad = results.first { it.connection.workspaceName == "ws-bad" }
        assertEquals(listOf("a"), good.specs.getOrThrow().map { it.id })  // success
        assertTrue(bad.specs.isFailure)                                   // isolated failure
    }
}
