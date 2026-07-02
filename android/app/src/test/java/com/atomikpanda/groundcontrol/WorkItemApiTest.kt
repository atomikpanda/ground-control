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
    fun get_item_parses_external_links() = runTest {
        val api = SpecApi(HttpClient(MockEngine {
            respond(
                """{"id":"wi-1","kind":"feature","title":"T","phase":"done",
                    "external_links":[{"provider":"github","url":"https://github.com/o/r/issues/1","title":"issue 1"},
                                      {"provider":"linear","url":"https://linear.app/x/MOS-1","title":""}]}""",
                HttpStatusCode.OK, jsonHdr,
            )
        }) { mshipDefaults() })
        val wi = api.getItem(conn, "wi-1")
        assertEquals(2, wi.externalLinks.size)
        assertEquals("github", wi.externalLinks[0].provider)
        assertEquals("https://github.com/o/r/issues/1", wi.externalLinks[0].url)
        assertEquals("issue 1", wi.externalLinks[0].title)
        assertEquals("", wi.externalLinks[1].title)
    }
}
