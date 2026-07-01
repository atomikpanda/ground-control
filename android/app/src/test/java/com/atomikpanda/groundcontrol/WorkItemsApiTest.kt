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
import io.ktor.http.HttpHeaders.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class WorkItemsApiTest {
    private val conn = WorkspaceConnection("1", "http://h:47100", "secret", "ws")
    private val jsonHdr = headersOf(ContentType, "application/json")

    @Test
    fun list_items_path_auth_and_parse() = runTest {
        var url: String? = null
        var auth: String? = null
        val api = SpecApi(HttpClient(MockEngine { req ->
            url = req.url.toString(); auth = req.headers[HttpHeaders.Authorization]
            respond(
                """[{"id":"wi-1","kind":"feature","title":"T","phase":"ready","attention":{"needs_approval":true}}]""",
                HttpStatusCode.OK, jsonHdr,
            )
        }) { mshipDefaults() })

        val items = api.listItems(conn)
        assertEquals(1, items.size)
        assertEquals("ready", items[0].phase)
        assertTrue(items[0].attention.needsApproval)
        assertTrue(url!!.endsWith("/items"))
        assertEquals("Bearer secret", auth)
    }
}
