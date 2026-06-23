package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
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

class ThreadsRepositoryTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun aggregates_and_isolates_failures() = runTest {
        val ok = WorkspaceConnection("1", "http://ok:47100", null, "ws-ok")
        val bad = WorkspaceConnection("2", "http://bad:47100", null, "ws-bad")
        val api = SpecApi(HttpClient(MockEngine { req ->
            if (req.url.host == "ok") respond(
                """[{"id":"t1","subject":"Idea"}]""",
                HttpStatusCode.OK,
                jsonHdr
            )
            else respond("boom", HttpStatusCode.InternalServerError)
        }) { install(ContentNegotiation) { json(buildJson()) } })
        val results = ThreadsRepository(api).listAllThreads(listOf(ok, bad))
        assertEquals(2, results.size)
        assertTrue(results.first { it.connection.id == "1" }.threads.isSuccess)
        assertTrue(results.first { it.connection.id == "2" }.threads.isFailure)
    }
}
