package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.deriveConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.data.upsertConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host workspace discovery (#472): list, derive, upsert-preservation, compat. */
class HostWorkspacesTest {
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private val listPayload = """
        {"workspaces":[
          {"id":"ws-1","name":"product","state":"healthy","path":"/src/product"},
          {"id":"ws-2","name":"internal","state":"degraded","detail":"invalid yaml","path":"/src/internal"}
        ]}
    """.trimIndent()

    @Test fun listWorkspaces_parses_and_keeps_degraded_entries() = runTest {
        var url: String? = null
        var auth: String? = null
        val api = SpecApi(HttpClient(MockEngine { req ->
            url = req.url.toString(); auth = req.headers[HttpHeaders.Authorization]
            respond(listPayload, HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() })

        val ws = api.listWorkspaces("http://host:47190/", "hosttok")
        assertEquals("http://host:47190/workspaces", url)
        assertEquals("Bearer hosttok", auth)
        assertEquals(listOf("ws-1", "ws-2"), ws.map { it.id })
        // degraded carried with state, never dropped
        assertEquals("degraded", ws[1].state)
        assertEquals("invalid yaml", ws[1].detail)
    }

    @Test fun derived_connection_has_host_scoped_id_and_prefixed_baseUrl() {
        val conn = deriveConnection(
            hostBase = "http://host:47190/", hostToken = "tok",
            hostId = "http://host:47190", workspaceId = "ws-1",
            workspaceName = "product", state = "healthy",
        )
        assertTrue(conn.id.startsWith("ws-1-")) // server id, scoped per host
        assertEquals("http://host:47190/workspaces/ws-1", conn.baseUrl)
        assertEquals("http://host:47190", conn.hostId)
        assertEquals("healthy", conn.state)
    }

    @Test fun derived_id_is_stable_for_a_host_and_url_safe() {
        // Connection ids are interpolated raw into nav routes — no slashes,
        // colons, or anything needing encoding.
        val a = deriveConnection("http://host:47190/", "t", "http://host:47190", "ws-1", "p", "healthy")
        val b = deriveConnection("http://host:47190", "t", "http://host:47190", "ws-1", "p", "healthy")
        assertEquals(a.id, b.id) // trailing slash normalized away → stable id
        assertTrue(a.id.matches(Regex("[A-Za-z0-9._~-]+")))
    }

    @Test fun same_workspace_id_on_two_hosts_keeps_both_connections() {
        // #472 allows the same logical workspace on multiple hosts; adding it
        // from host B must not evict the host A connection.
        val fromA = deriveConnection("http://host-a:1", "t", "http://host-a:1", "ws-1", "product", "healthy")
        val fromB = deriveConnection("http://host-b:1", "t", "http://host-b:1", "ws-1", "product", "healthy")
        val list = upsertConnection(upsertConnection(emptyList(), fromA), fromB)
        assertEquals(2, list.size)
        assertEquals(2, list.map { it.id }.toSet().size)
    }

    @Test fun rediscovery_upsert_preserves_identity_overrides() {
        val first = deriveConnection("http://h:1", "t", "http://h:1", "ws-1", "product", "healthy")
        var list = upsertConnection(emptyList(), first)
        // operator customizes the badge
        list = list.map { it.copy(colorOverride = "#FF112233", glyphOverride = "P") }
        // re-discovery derives the same connection fresh (no overrides on it)
        val again = deriveConnection("http://h:1", "t", "http://h:1", "ws-1", "product", "healthy")
        list = upsertConnection(list, again)
        assertEquals(1, list.size)
        assertEquals("#FF112233", list[0].colorOverride)
        assertEquals("P", list[0].glyphOverride)
    }

    @Test fun two_workspaces_from_one_host_are_two_connections() {
        val a = deriveConnection("http://h:1", "t", "http://h:1", "ws-1", "product", "healthy")
        val b = deriveConnection("http://h:1", "t", "http://h:1", "ws-2", "internal", "healthy")
        val list = upsertConnection(upsertConnection(emptyList(), a), b)
        assertEquals(2, list.size)
        assertTrue(list.map { it.baseUrl }.toSet().size == 2)
    }

    @Test fun old_persisted_json_without_new_fields_still_deserializes() {
        // Captured pre-#472 stored shape: no hostId, no state.
        val old = """[{"id":"1","baseUrl":"http://host:47100","token":"tok","workspaceName":"ws-a"}]"""
        val decoded = ConnectionsCodec.decode(old)
        assertEquals(1, decoded.size)
        assertNull(decoded[0].hostId)
        assertNull(decoded[0].state)
        assertEquals("ws-a", decoded[0].workspaceName)
    }

    @Test fun manual_and_discovered_same_name_stay_two_entries_pinned() {
        // KNOWN duplicate (documented): a pre-existing manually paired
        // connection (old per-workspace URL, locally minted id) matches a
        // discovery-derived upsert on neither id nor baseUrl — two entries
        // until the operator removes the stale manual one. Migration is #471's
        // (when the manual path disappears). This pins today's behavior so a
        // silent change is visible.
        val manual = WorkspaceConnection("local-uuid", "http://host:47100", "tok", "product")
        val discovered = deriveConnection("http://host:47190", "tok", "http://host:47190", "ws-1", "product", "healthy")
        val list = upsertConnection(listOf(manual), discovered)
        assertEquals(2, list.size)
    }
}
