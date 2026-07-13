// app/src/test/java/com/atomikpanda/groundcontrol/CaptureApiTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureApiTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun capture_brainstorm_posts_idea_to_capture_with_auth() = runTest {
        var url: String? = null
        var auth: String? = null
        var body: String? = null
        val api = SpecApi(HttpClient(MockEngine { req ->
            url = req.url.toString(); auth = req.headers[HttpHeaders.Authorization]
            body = (req.body as io.ktor.http.content.TextContent).text
            respond(
                """{"id":"t1","subject":"a queue tab","messages":[{"id":"m1","thread_id":"t1","role":"human","text":"a queue tab","kind":"note"}]}""",
                HttpStatusCode.OK, jsonHdr,
            )
        }) { mshipDefaults() })

        val thread = api.captureBrainstorm(conn, "a queue tab")
        assertEquals("t1", thread.id)
        assertTrue(url!!.endsWith("/capture"))
        assertEquals("Bearer secret", auth)
        assertTrue(body!!.contains("\"idea\":\"a queue tab\""))
    }
}
