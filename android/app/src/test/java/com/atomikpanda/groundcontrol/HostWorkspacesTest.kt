package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.FLEET_TOKEN_HEADER
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.adoptManualConnections
import com.atomikpanda.groundcontrol.data.deriveConnection
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.hostAwareClient
import com.atomikpanda.groundcontrol.data.hostBase
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.data.refreshHostWorkspaceConnections
import com.atomikpanda.groundcontrol.data.recordHostContact
import com.atomikpanda.groundcontrol.data.upsertConnection
import com.atomikpanda.groundcontrol.data.verifyLegacyIdentities
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host workspace discovery (#472) and the relay directory + refresh exchange (#471). */
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
        assertEquals("ws-1", conn.workspaceId)
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
        // Captured pre-#472 stored shape: no hostId, no state, no workspaceId.
        val old = """[{"id":"1","baseUrl":"http://host:47100","token":"tok","workspaceName":"ws-a"}]"""
        val decoded = ConnectionsCodec.decode(old)
        assertEquals(1, decoded.size)
        assertNull(decoded[0].hostId)
        assertNull(decoded[0].state)
        assertNull(decoded[0].workspaceId)
        assertEquals(emptyList<String>(), decoded[0].legacyBaseUrls)
        assertEquals("ws-a", decoded[0].workspaceName)
    }

    // ---- adoption merges on identity, never on names (#471) ----------------

    private val manual = WorkspaceConnection(
        id = "local-uuid", baseUrl = "http://host-a:47100", token = "standing-tok",
        workspaceName = "alpha", colorOverride = "#FF112233", glyphOverride = "A",
    )

    @Test fun a_same_name_workspace_on_another_host_is_not_adopted() {
        // Names are ambiguous by #472's premise: the same logical workspace may
        // live on several hosts, and two unrelated ones may share a name. The
        // manual row's own host says host-a; the discovered row is on host-b.
        val discovered = deriveConnection("https://b.relay", null, "host-b", "ws-1", "alpha", "healthy")
        val out = adoptManualConnections(
            existing = listOf(manual),
            discovered = listOf(discovered),
            identities = listOf(VerifiedIdentity("local-uuid", hostId = "host-a", workspaceId = "ws-1")),
        )
        assertEquals(2, out.size)
    }

    @Test fun an_unverified_manual_row_is_not_adopted() {
        // The probe failed (host down, or a multi-workspace host the row can't be
        // attributed to): no tuple, no merge — two rows is the honest answer.
        val discovered = deriveConnection("https://a.relay", null, "host-a", "ws-1", "alpha", "healthy")
        val out = adoptManualConnections(
            existing = listOf(manual),
            discovered = listOf(discovered),
            identities = listOf(VerifiedIdentity("local-uuid", hostId = null, workspaceId = null)),
        )
        assertEquals(2, out.size)
    }

    @Test fun a_verified_tuple_merges_into_one_row_keeping_the_operators_identity() {
        val discovered = deriveConnection("https://a.relay", null, "host-a", "ws-1", "alpha", "healthy")
        val out = adoptManualConnections(
            existing = listOf(manual),
            discovered = listOf(discovered),
            identities = listOf(VerifiedIdentity("local-uuid", hostId = "host-a", workspaceId = "ws-1")),
        )
        assertEquals(1, out.size)
        val row = out[0]
        assertEquals("local-uuid", row.id)             // nav routes + notification ids survive
        assertEquals("#FF112233", row.colorOverride)
        assertEquals("A", row.glyphOverride)
        assertEquals("host-a", row.hostId)
        assertEquals("ws-1", row.workspaceId)
        assertEquals("healthy", row.state)
        assertEquals("https://a.relay/workspaces/ws-1", row.baseUrl)
        // Already-issued PendingIntents carry the pre-adoption URL.
        assertEquals(listOf("http://host-a:47100"), row.legacyBaseUrls)
        // The standing token is dropped: it is rejected for relay-borne requests,
        // and holding one would suppress the refresh interceptor's bearer.
        assertNull(row.token)
    }

    @Test fun a_verified_legacy_host_handle_is_adopted_to_the_real_host_id() {
        val legacy = WorkspaceConnection(
            id = "legacy-row",
            baseUrl = "http://lan:47190/workspaces/ws-2",
            token = "standing",
            workspaceName = "internal",
            hostId = "http://lan:47190",
            workspaceId = "ws-2",
        )
        val discovered = deriveConnection(
            "https://a.relay",
            null,
            "host-a",
            "ws-2",
            "internal",
            "healthy",
        )
        val out = adoptManualConnections(
            existing = listOf(legacy),
            discovered = listOf(discovered),
            identities = listOf(VerifiedIdentity("legacy-row", "host-a", "ws-2")),
        )
        assertEquals(1, out.size)
        assertEquals("legacy-row", out.single().id)
        assertEquals("host-a", out.single().hostId)
        assertEquals(listOf("http://lan:47190/workspaces/ws-2"), out.single().legacyBaseUrls)
    }

    @Test fun adoption_is_pure_and_idempotent() {
        val existing = listOf(manual)
        val discovered = deriveConnection("https://a.relay", null, "host-a", "ws-1", "alpha", "healthy")
        val ids = listOf(VerifiedIdentity("local-uuid", "host-a", "ws-1"))
        val once = adoptManualConnections(existing, listOf(discovered), ids)
        val twice = adoptManualConnections(once, listOf(discovered), ids)
        assertEquals(once, twice)
        assertEquals(listOf(manual), existing)   // the input list is untouched
    }

    @Test fun two_hosts_each_with_workspaces_are_two_host_groups() {
        // AC5.
        val a = HostConnection(hostId = "host-a", publicUrl = "https://a.relay")
        val b = HostConnection(hostId = "host-b", publicUrl = "https://b.relay")
        val list = listOf(a to "ws-1", a to "ws-2", b to "ws-1")
            .fold(emptyList<WorkspaceConnection>()) { acc, (h, w) ->
                upsertConnection(acc, deriveConnection(h, WorkspaceInfo(id = w, name = w, state = "healthy")))
            }
        assertEquals(3, list.size)
        assertEquals(setOf("host-a", "host-b"), list.mapNotNull { it.hostId }.toSet())
        assertEquals(2, list.count { it.hostId == "host-a" })
    }

    @Test fun rediscovery_is_keyed_on_host_and_workspace_not_the_url() {
        // A host that moves subdomains (re-registration, relay redeploy) must
        // update the row, not fork it — the URL is derived, so it is not a key.
        val host = HostConnection(hostId = "host-a", publicUrl = "https://old.relay")
        val ws = WorkspaceInfo(id = "ws-1", name = "alpha", state = "healthy")
        val first = deriveConnection(host, ws)
        val moved = deriveConnection(host.copy(publicUrl = "https://new.relay"), ws)
        val list = upsertConnection(upsertConnection(emptyList(), first), moved)
        assertEquals(1, list.size)
        assertEquals("https://new.relay/workspaces/ws-1", list[0].baseUrl)
    }

    // ---- the relay directory ------------------------------------------------

    private val hostsPayload = """
        {"hosts":[
          {"host_id":"host-a","state":"online","label":"vm-a","subdomain":"sub-a",
           "public_url":"https://sub-a.relay.example.com","refresh":"cred-a","last_seen":1.0,
           "runner":{"enabled":false,"state":"disabled"}},
          {"host_id":null,"state":"pending-approval","label":"vm-new","request_id":"req-1"}
        ]}
    """.trimIndent()

    @Test fun listHosts_reads_the_directory_with_the_fleet_token() = runTest {
        var url: String? = null
        var fleet: String? = null
        val api = SpecApi(HttpClient(MockEngine { req ->
            url = req.url.toString(); fleet = req.headers[FLEET_TOKEN_HEADER]
            respond(hostsPayload, HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() })

        val hosts = api.listHosts("relay.example.com", "fleet-tok")
        assertEquals("https://enroll.relay.example.com/hosts", url)
        assertEquals("fleet-tok", fleet)
        assertEquals(2, hosts.size)
        assertEquals("cred-a", hosts[0].refresh)
        assertEquals("https://sub-a.relay.example.com", hosts[0].publicUrl)
        // A freshly provisioned VM is visible before anyone approves it (AC1).
        assertEquals("pending-approval", hosts[1].state)
        assertNull(hosts[1].hostId)
    }

    // ---- the host-scoped refresh exchange (AC9) -----------------------------

    private val host = HostConnection(
        hostId = "host-a", publicUrl = "https://sub-a.relay.example.com",
        refresh = "cred-a", state = "online",
    )

    /**
     * A host that mints bearers on demand and can reject the ones it already
     * issued. [unauthorizedCalls] is the set of 1-based workspace-call indices
     * answered 401; [barrierCalls] holds those calls until all of them have
     * arrived, so "concurrent" means concurrent rather than "fast enough".
     */
    private class HostFixture(
        val unauthorizedCalls: Set<Int> = emptySet(),
        val failRefreshFrom: Int = Int.MAX_VALUE,
        val barrierCalls: Set<Int> = emptySet(),
    ) {
        private val lock = Any()
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String?>()
        val refreshBodies = mutableListOf<String>()
        var mints = 0; private set
        private var wsCalls = 0
        private var refreshAttempts = 0
        private var arrived = 0
        private val gate = CompletableDeferred<Unit>()

        val handler: MockRequestHandler = handler@{ req: HttpRequestData ->
            val url = req.url.toString()
            synchronized(lock) { urls += url }
            if (url.endsWith("/host/token")) {
                synchronized(lock) {
                    refreshAttempts += 1
                    refreshBodies += (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                }
                if (refreshAttempts >= failRefreshFrom) {
                    return@handler respond(
                        """{"detail":"invalid or expired refresh credential"}""",
                        HttpStatusCode.Unauthorized, jsonHeaders,
                    )
                }
                val n = synchronized(lock) { mints += 1; mints }
                return@handler respond("""{"token":"bearer-$n","expires_in":300}""", HttpStatusCode.OK, jsonHeaders)
            }
            val index = synchronized(lock) {
                bearers += req.headers[HttpHeaders.Authorization]
                wsCalls += 1
                wsCalls
            }
            if (index in barrierCalls) {
                val reached = synchronized(lock) { arrived += 1; arrived }
                if (reached >= barrierCalls.size) gate.complete(Unit)
                withTimeout(5_000) { gate.await() }
            }
            if (index in unauthorizedCalls) {
                respond("""{"detail":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            } else {
                respond(
                    """{"workspaces":[{"id":"ws-1","name":"alpha","state":"healthy"}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }

        val engine = MockEngine(handler)

        private companion object {
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        }
    }

    @Test fun a_host_read_mints_a_bearer_from_the_persisted_refresh() = runTest {
        val fx = HostFixture()
        val hc = hostAwareClient(fx.engine) { listOf(host) }
        val ws = SpecApi(hc.client).listWorkspaces(host.hostBase(), null)

        assertEquals(listOf("ws-1"), ws.map { it.id })
        assertEquals(1, fx.mints)
        // The exchange runs against the HOST's own URL — never proxied through the
        // relay directory — and carries the credential, not an Authorization header.
        assertEquals("https://sub-a.relay.example.com/host/token", fx.urls.first())
        assertTrue(fx.refreshBodies.single().contains("cred-a"))
        assertEquals(listOf("Bearer bearer-1"), fx.bearers)
    }

    @Test fun a_401_refreshes_exactly_once_and_retries_once() = runTest {
        // Call 1 mints and succeeds; call 2 is answered 401 (the host expired the
        // bearer), which must produce exactly one more exchange and one retry.
        val fx = HostFixture(unauthorizedCalls = setOf(2))
        val hc = hostAwareClient(fx.engine) { listOf(host) }
        val api = SpecApi(hc.client)

        api.listWorkspaces(host.hostBase(), null)
        val ws = api.listWorkspaces(host.hostBase(), null)

        assertEquals(listOf("ws-1"), ws.map { it.id })
        assertEquals(2, fx.mints)   // the initial mint + exactly one refresh
        assertEquals(listOf("Bearer bearer-1", "Bearer bearer-1", "Bearer bearer-2"), fx.bearers)
    }

    @Test fun two_concurrent_401s_cause_one_refresh() = runBlocking {
        // Real dispatchers and a real barrier: the second caller must find the
        // first exchange in flight, not a virtual clock that already ran it.
        val fx = HostFixture(unauthorizedCalls = setOf(2, 3), barrierCalls = setOf(2, 3))
        val hc = hostAwareClient(fx.engine) { listOf(host) }
        val api = SpecApi(hc.client)
        api.listWorkspaces(host.hostBase(), null)   // prime bearer-1 (call 1)

        val both = withContext(Dispatchers.Default) {
            listOf(
                async { api.listWorkspaces(host.hostBase(), null) },
                async { api.listWorkspaces(host.hostBase(), null) },
            ).awaitAll()
        }

        assertEquals(2, both.size)
        assertTrue(both.all { it.map { w -> w.id } == listOf("ws-1") })
        assertEquals(2, fx.mints)   // the initial mint + ONE shared refresh, not two
        assertEquals(listOf("Bearer bearer-2", "Bearer bearer-2"), fx.bearers.drop(3))
    }

    @Test fun a_401_on_the_refresh_itself_asks_for_a_re_pair_and_does_not_loop() = runTest {
        val fx = HostFixture(unauthorizedCalls = setOf(2), failRefreshFrom = 2)
        val hc = hostAwareClient(fx.engine) { listOf(host) }
        val api = SpecApi(hc.client)
        api.listWorkspaces(host.hostBase(), null)   // mint 1 succeeds

        val err = runCatching { api.listWorkspaces(host.hostBase(), null) }.exceptionOrNull()
        assertTrue("$err", err is RePairNeededException)
        assertEquals(host.hostBase(), (err as RePairNeededException).hostBase)
        assertEquals(1, fx.mints)
        assertEquals(2, fx.refreshBodies.size)  // one failed attempt, not a retry loop
    }

    @Test fun relay_down_host_up_keeps_the_workspace_usable() = runBlocking {
        // GET /hosts fails, but the persisted refresh still mints against the cached
        // public URL (or the LAN one), so a workspace never goes dark with the relay.
        val fx = HostFixture()
        val lan = host.copy(directUrl = "http://192.168.1.9:47190")
        val engine = MockEngine { req ->
            if (req.url.host.startsWith("enroll.")) throw java.io.IOException("relay unreachable")
            fx.handler(this, req)
        }
        val hc = hostAwareClient(engine) { listOf(lan) }
        val api = SpecApi(hc.client)

        assertNull(runCatching { api.listHosts("relay.example.com", "fleet-tok") }.getOrNull())
        val ws = api.listWorkspaces(lan.hostBase(), null)
        assertEquals(listOf("ws-1"), ws.map { it.id })
        assertEquals("http://192.168.1.9:47190/host/token", fx.urls.first())
    }

    @Test fun direct_down_public_relay_up_uses_the_reachable_public_base() = runTest {
        val urls = mutableListOf<String>()
        val api = SpecApi(HttpClient(MockEngine { req ->
            urls += req.url.toString()
            if (req.url.host == "192.168.1.9") throw java.io.IOException("left the LAN")
            respond(listPayload, HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() })
        val withLan = host.copy(directUrl = "http://192.168.1.9:47190")

        val refreshed = refreshHostWorkspaceConnections(api, withLan)!!

        assertEquals(listOf("ws-1", "ws-2"), refreshed.connections.map { it.workspaceId })
        assertEquals(
            "https://sub-a.relay.example.com/workspaces/ws-1",
            refreshed.connections.first().baseUrl,
        )
        assertEquals(
            listOf(
                "http://192.168.1.9:47190/workspaces",
                "https://sub-a.relay.example.com/workspaces",
            ),
            urls,
        )
    }


    @Test fun host_client_falls_back_from_a_dead_direct_base_to_the_public_base() = runTest {
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val withLan = host.copy(directUrl = "http://192.168.1.9:47190")
        val client = hostAwareClient(
            engine = MockEngine { req ->
                urls += req.url.toString()
                if (req.url.host == "192.168.1.9") throw java.io.IOException("left the LAN")
                when (req.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"public-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces" -> {
                        authorizations += req.headers[HttpHeaders.Authorization]
                        respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(withLan) },
        )

        val refreshed = refreshHostWorkspaceConnections(
            SpecApi(client.client),
            withLan,
        )!!

        assertEquals(listOf("ws-1", "ws-2"), refreshed.connections.map { it.workspaceId })
        assertEquals(
            "https://sub-a.relay.example.com/workspaces/ws-1",
            refreshed.connections.first().baseUrl,
        )
        assertEquals(
            listOf(
                "http://192.168.1.9:47190/host/token",
                "https://sub-a.relay.example.com/host/token",
                "https://sub-a.relay.example.com/workspaces",
            ),
            urls,
        )
        assertEquals(listOf("Bearer public-bearer"), authorizations)
    }

    @Test fun fallback_mints_a_bearer_for_each_candidate_base() = runTest {
        val urls = mutableListOf<String>()
        val publicAuthorizations = mutableListOf<String?>()
        val withLan = host.copy(directUrl = "http://192.168.1.9:47190")
        val client = hostAwareClient(
            engine = MockEngine { req ->
                urls += req.url.toString()
                when {
                    req.url.encodedPath == "/host/token" -> respond(
                        """{"token":"${req.url.host}-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    req.url.encodedPath == "/workspaces" &&
                        req.url.host == "192.168.1.9" ->
                        throw java.io.IOException("response lost")
                    req.url.encodedPath == "/workspaces" -> {
                        publicAuthorizations += req.headers[HttpHeaders.Authorization]
                        respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(withLan) },
        )

        SpecApi(client.client).listWorkspaces(withLan.directUrl!!, null)

        assertEquals(
            listOf(
                "http://192.168.1.9:47190/host/token",
                "http://192.168.1.9:47190/workspaces",
                "https://sub-a.relay.example.com/host/token",
                "https://sub-a.relay.example.com/workspaces",
            ),
            urls,
        )
        assertEquals(listOf("Bearer sub-a.relay.example.com-bearer"), publicAuthorizations)
    }

    @Test fun stale_workspace_url_routes_to_the_hosts_current_public_url_before_send() = runTest {
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val rotated = host.copy(publicUrl = "https://sub-b.relay.example.com")
        val client = hostAwareClient(
            engine = MockEngine { req ->
                urls += req.url.toString()
                when (req.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"rotated-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces/ws-1/threads/thread-1/seen" -> {
                        authorizations += req.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(rotated) },
        )
        val stale = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "https://sub-a.relay.example.com/workspaces/ws-1",
            token = null,
            workspaceName = "ws",
            hostId = rotated.hostId,
            workspaceId = "ws-1",
        )

        SpecApi(client.client).markThreadSeen(stale, "thread-1", null)

        assertEquals(
            listOf(
                "https://sub-b.relay.example.com/host/token",
                "https://sub-b.relay.example.com/workspaces/ws-1/threads/thread-1/seen",
            ),
            urls,
        )
        assertEquals(listOf("Bearer rotated-bearer"), authorizations)
    }
    @Test fun evidence_blob_uses_the_hosts_current_route_and_a_minted_bearer() = runTest {
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val rotated = host.copy(publicUrl = "https://sub-b.relay.example.com")
        val client = hostAwareClient(
            engine = MockEngine { req ->
                urls += req.url.toString()
                when (req.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"rotated-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces/ws-1/specs/spec-1/evidence/a1b2c3.png/blob" -> {
                        authorizations += req.headers[HttpHeaders.Authorization]
                        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(rotated) },
        )
        val stale = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "https://sub-a.relay.example.com/workspaces/ws-1",
            token = null,
            workspaceName = "ws",
            hostId = rotated.hostId,
            workspaceId = "ws-1",
        )

        val bytes = SpecApi(client.client).getEvidenceBlob(
            stale,
            "spec-1",
            "a1b2c3.png",
        )

        assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
        assertEquals(
            listOf(
                "https://sub-b.relay.example.com/host/token",
                "https://sub-b.relay.example.com/workspaces/ws-1/specs/spec-1/evidence/a1b2c3.png/blob",
            ),
            urls,
        )
        assertEquals(listOf("Bearer rotated-bearer"), authorizations)
    }

    @Test fun an_ambiguous_write_failure_is_not_replayed_through_the_public_base() = runTest {
        val urls = mutableListOf<String>()
        var directDown = false
        val withLan = host.copy(directUrl = "http://192.168.1.9:47190")
        val client = hostAwareClient(
            engine = MockEngine { req ->
                urls += req.url.toString()
                if (directDown && req.url.host == "192.168.1.9") {
                    throw java.io.IOException("response lost after send")
                }
                when (req.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces" -> respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    else -> respond("{}", HttpStatusCode.OK, jsonHdr)
                }
            },
            hosts = { listOf(withLan) },
        )
        val api = SpecApi(client.client)
        api.listWorkspaces(withLan.directUrl!!, null)
        directDown = true

        val result = runCatching {
            api.markThreadSeen(
                WorkspaceConnection(
                    id = "ws-1",
                    baseUrl = "${withLan.directUrl}/workspaces/ws-1",
                    token = null,
                    workspaceName = "ws",
                    hostId = withLan.hostId,
                    workspaceId = "ws-1",
                ),
                "thread-1",
                null,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(
            urls.none {
                it == "https://sub-a.relay.example.com/workspaces/ws-1/threads/thread-1/seen"
            },
        )
    }

    @Test fun fleet_workspace_probe_defers_contact_to_the_atomic_fleet_apply() = runTest {
        val contacts = mutableListOf<String>()
        val client = hostAwareClient(
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces" -> respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(host) },
            onHostContact = { contacts += it },
        )

        assertTrue(refreshHostWorkspaceConnections(SpecApi(client.client), host) != null)
        assertEquals(emptyList<String>(), contacts)
    }


    @Test fun one_legacy_repair_failure_does_not_block_later_verified_adoptions() = runTest {
        val badHost = host.copy(
            hostId = "host-bad",
            publicUrl = "https://bad.relay.example.com",
            refresh = "bad-refresh",
        )
        val goodHost = host.copy(
            hostId = "host-good",
            publicUrl = "https://good.relay.example.com",
            refresh = "good-refresh",
        )
        val client = hostAwareClient(
            engine = MockEngine { req ->
                when {
                    req.url.host == "bad.relay.example.com" &&
                        req.url.encodedPath == "/host/token" ->
                        respond(
                            """{"detail":"unauthorized"}""",
                            HttpStatusCode.Unauthorized,
                            jsonHdr,
                        )
                    req.url.encodedPath == "/host/token" -> respond(
                        """{"token":"bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    req.url.encodedPath == "/health" -> respond(
                        """{"status":"ok","host_id":"host-good","workspaces":2,"degraded":0}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    req.url.encodedPath == "/workspaces" ->
                        respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(badHost, goodHost) },
        )
        val bad = WorkspaceConnection(
            id = "bad",
            baseUrl = "${badHost.publicUrl}/workspaces/ws-1",
            hostId = badHost.publicUrl,
            workspaceId = "ws-1",
        )
        val good = WorkspaceConnection(
            id = "good",
            baseUrl = "${goodHost.publicUrl}/workspaces/ws-1",
            hostId = goodHost.publicUrl,
            workspaceId = "ws-1",
        )

        val verification = verifyLegacyIdentities(
            SpecApi(client.client),
            listOf(bad, good),
        )

        assertTrue(verification.requiresRePair)
        assertEquals(listOf("good"), verification.identities.map { it.connectionId })
    }

    @Test fun refresh_fleet_adopts_a_legacy_row_through_host_root_with_multiple_workspaces() = runTest {
        val urls = mutableListOf<String>()
        val api = SpecApi(HttpClient(MockEngine { req ->
            urls += req.url.toString()
            when (req.url.encodedPath) {
                "/health" -> respond(
                    """{"status":"ok","host_id":"host-a","instance_id":"i-1","workspaces":2,"degraded":0}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
                "/workspaces" -> respond(listPayload, HttpStatusCode.OK, jsonHdr)
                else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
            }
        }) { mshipDefaults() })
        val legacy = WorkspaceConnection(
            id = "legacy-row",
            baseUrl = "http://lan:47190/workspaces/ws-2",
            token = "standing",
            workspaceName = "internal",
            hostId = "http://lan:47190",
            workspaceId = "ws-2",
        )
        val identities = verifyLegacyIdentities(api, listOf(legacy)).identities
        val refreshed = refreshHostWorkspaceConnections(
            api,
            HostConnection(hostId = "host-a", publicUrl = "http://lan:47190"),
            identities = identities,
        )!!
        val adopted = adoptManualConnections(
            listOf(legacy),
            refreshed.connections,
            refreshed.identities,
        )

        assertEquals(2, adopted.size)
        val migrated = adopted.single { it.workspaceId == "ws-2" }
        assertEquals("legacy-row", migrated.id)
        assertEquals("host-a", migrated.hostId)
        assertEquals(1, adopted.count { it.workspaceId == "ws-2" })
        assertEquals(
            listOf(
                "http://lan:47190/health",
                "http://lan:47190/workspaces",
                "http://lan:47190/workspaces",
            ),
            urls,
        )
    }

    @Test fun ordinary_successful_host_traffic_advances_last_phone_contact() = runTest {
        var stored = listOf(host.copy(lastContactAtMillis = 10))
        val fx = HostFixture()
        val client = hostAwareClient(
            engine = fx.engine,
            hosts = { stored },
            onHostContact = { base -> stored = recordHostContact(stored, base, 20) },
        )

        SpecApi(client.client).listWorkspaces(host.hostBase(), null)

        assertEquals(20L, stored.single().lastContactAtMillis)
    }

    @Test fun out_of_order_contact_persistence_cannot_move_freshness_backwards() {
        val stored = listOf(host.copy(lastContactAtMillis = 20))

        val afterOlderWrite = recordHostContact(stored, host.hostBase(), 10)
        val afterNewerWrite = recordHostContact(afterOlderWrite, host.hostBase(), 30)

        assertEquals(20L, afterOlderWrite.single().lastContactAtMillis)
        assertEquals(30L, afterNewerWrite.single().lastContactAtMillis)
    }

    @Test fun contact_persistence_failure_does_not_fail_a_successful_api_call() = runTest {
        val fx = HostFixture()
        val client = hostAwareClient(
            engine = fx.engine,
            hosts = { listOf(host) },
            onHostContact = { throw IllegalStateException("DataStore write failed") },
        )

        val workspaces = SpecApi(client.client).listWorkspaces(host.hostBase(), null)

        assertEquals(listOf("ws-1"), workspaces.map { it.id })
    }

    @Test fun contact_persistence_cancellation_still_cancels_the_api_call() = runTest {
        val fx = HostFixture()
        val client = hostAwareClient(
            engine = fx.engine,
            hosts = { listOf(host) },
            onHostContact = { throw CancellationException("cancelled") },
        )

        val error = runCatching {
            SpecApi(client.client).listWorkspaces(host.hostBase(), null)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }
}
