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

class ThreadsApiTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(h: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(h)) { mshipDefaults() }

    @Test fun list_threads_path_and_auth() = runTest {
        var url: String? = null; var auth: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString(); auth = req.headers[HttpHeaders.Authorization]
            respond("""[{"id":"t1","subject":"s"}]""", HttpStatusCode.OK, jsonHdr)
        })
        assertEquals(1, api.listThreads(conn).size)
        assertTrue(url!!.endsWith("/threads")); assertEquals("Bearer secret", auth)
    }

    @Test fun create_thread_posts_text() = runTest {
        var body: String? = null
        val api = SpecApi(client { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"t9","subject":"My idea","messages":[]}""", HttpStatusCode.OK, jsonHdr)
        })
        val t = api.createThread(conn, "My idea", null)
        assertEquals("t9", t.id)
        assertTrue(body!!.contains("\"text\":\"My idea\""))
    }

    @Test fun post_message_hits_messages_path() = runTest {
        var url: String? = null
        val api = SpecApi(client { req ->
            url = req.url.toString()
            respond("""{"id":"t1","subject":"s","messages":[]}""", HttpStatusCode.OK, jsonHdr)
        })
        api.postMessage(conn, "t1", "hello")
        assertTrue(url!!.endsWith("/threads/t1/messages"))
    }

    @Test(expected = com.atomikpanda.groundcontrol.data.NotFoundException::class)
    fun get_thread_maps_404() = runTest {
        val api = SpecApi(client { respond("""{"detail":"no thread"}""", HttpStatusCode.NotFound, jsonHdr) })
        api.getThread(conn, "missing")
    }
}
