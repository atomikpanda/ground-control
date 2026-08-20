package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.FLEET_TOKEN_HEADER
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.data.RelayAccount
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
import com.atomikpanda.groundcontrol.data.reachableHostWorkspaces
import com.atomikpanda.groundcontrol.data.replaceRelayAccountFleet
import com.atomikpanda.groundcontrol.data.replaceHostConnections
import com.atomikpanda.groundcontrol.data.recordHostContact
import com.atomikpanda.groundcontrol.data.upsertConnection
import com.atomikpanda.groundcontrol.data.verifyLegacyIdentities
import com.atomikpanda.groundcontrol.data.unresolvedLegacyConnections
import com.atomikpanda.groundcontrol.ui.settings.legacyConnectionsForDiscovery
import com.atomikpanda.groundcontrol.ui.settings.fleetWorkspaceRefreshTargets
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

    @Test fun verified_direct_adoption_keeps_the_direct_token() {
        val directManual = manual.copy(
            baseUrl = "http://host-a:47100/workspaces/ws-1",
        )
        val discovered = deriveConnection(
            "http://host-a:47100",
            "standing-tok",
            "host-a",
            "ws-1",
            "alpha",
            "healthy",
        )
        val out = adoptManualConnections(
            existing = listOf(directManual),
            discovered = listOf(discovered),
            identities = listOf(VerifiedIdentity("local-uuid", "host-a", "ws-1")),
        )

        assertEquals(1, out.size)
        assertEquals("local-uuid", out.single().id)
        assertEquals("standing-tok", out.single().token)
    }
    @Test fun verified_tokenless_direct_adoption_reactivates_the_prior_direct_token() {
        val directManual = manual.copy(
            baseUrl = "http://host-a:47100/workspaces/ws-1",
        )
        val discovered = deriveConnection(
            "http://host-a:47100",
            null,
            "host-a",
            "ws-1",
            "alpha",
            "healthy",
        )

        val adopted = adoptManualConnections(
            existing = listOf(directManual),
            discovered = listOf(discovered),
            identities = listOf(VerifiedIdentity("local-uuid", "host-a", "ws-1")),
            activatePriorDirectToken = true,
        ).single()

        assertEquals("standing-tok", adopted.token)
        assertEquals("standing-tok", adopted.directToken)
    }


    @Test fun verified_adoption_preserves_urls_from_an_existing_discovered_twin() {
        val twin = deriveConnection(
            "https://old.relay", null, "host-a", "ws-1", "alpha", "healthy",
        ).copy(legacyBaseUrls = listOf("https://older.relay/workspaces/ws-1"))
        val refreshed = deriveConnection(
            "https://new.relay", null, "host-a", "ws-1", "alpha", "healthy",
        )
        val out = adoptManualConnections(
            existing = listOf(manual, twin),
            discovered = listOf(refreshed),
            identities = listOf(VerifiedIdentity("local-uuid", "host-a", "ws-1")),
        )

        assertEquals(1, out.count { it.hostId == "host-a" && it.workspaceId == "ws-1" })
        assertEquals("local-uuid", out.single().id)
        assertEquals(
            listOf(
                "http://host-a:47100",
                "https://older.relay/workspaces/ws-1",
                "https://old.relay/workspaces/ws-1",
            ),
            out.single().legacyBaseUrls,
        )
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
        val expiresInSeconds: Int = 300,
        val onMint: () -> Unit = {},
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
                onMint()
                return@handler respond(
                    """{"token":"bearer-$n","expires_in":$expiresInSeconds}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
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

    @Test fun a_401_does_not_replay_a_non_idempotent_request() = runTest {
        val fx = HostFixture(unauthorizedCalls = setOf(2))
        val client = hostAwareClient(fx.engine) { listOf(host) }
        val api = SpecApi(client.client)
        api.listWorkspaces(host.hostBase(), null)
        val connection = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "${host.hostBase()}/workspaces/ws-1",
            hostId = host.hostId,
            workspaceId = "ws-1",
        )

        val error = runCatching {
            api.markThreadSeen(connection, "thread-1", null)
        }.exceptionOrNull()

        assertTrue("$error", error is AuthException)
        assertEquals(1, fx.mints)
        assertEquals(2, fx.bearers.size)
    }

    @Test fun an_expired_cached_bearer_is_replaced_before_a_write() = runTest {
        var now = 0L
        val fx = HostFixture(expiresInSeconds = 1)
        val client = hostAwareClient(
            engine = fx.engine,
            nowMillis = { now },
            hosts = { listOf(host) },
        )
        val api = SpecApi(client.client)
        api.listWorkspaces(host.hostBase(), null)
        now = 1_000L
        api.markThreadSeen(
            WorkspaceConnection(
                id = "ws-1",
                baseUrl = "${host.hostBase()}/workspaces/ws-1",
                hostId = host.hostId,
                workspaceId = "ws-1",
            ),
            "thread-1",
            null,
        )

        assertEquals(2, fx.mints)
        assertEquals("Bearer bearer-2", fx.bearers.last())
    }

    @Test fun exchange_latency_is_not_added_to_a_bearers_write_lifetime() = runTest {
        var now = 0L
        val fx = HostFixture(
            expiresInSeconds = 3,
            onMint = { now += 2_000L },
        )
        val client = hostAwareClient(
            engine = fx.engine,
            nowMillis = { now },
            hosts = { listOf(host) },
        )
        val api = SpecApi(client.client)
        api.listWorkspaces(host.hostBase(), null)
        now = 2_800L

        api.markThreadSeen(
            WorkspaceConnection(
                id = "ws-1",
                baseUrl = "${host.hostBase()}/workspaces/ws-1",
                hostId = host.hostId,
                workspaceId = "ws-1",
            ),
            "thread-1",
            null,
        )

        assertEquals(2, fx.mints)
        assertEquals("Bearer bearer-2", fx.bearers.last())
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

    @Test fun relay_directory_down_public_host_up_keeps_the_workspace_usable() = runBlocking {
        // Directory failure does not matter once the public host URL and refresh
        // are cached. An unverified direct URL is deliberately ignored.
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
        assertEquals("https://sub-a.relay.example.com/host/token", fx.urls.first())
    }

    @Test fun route_snapshot_cannot_gain_a_refresh_before_exchange() = runTest {
        val directOnly = host.copy(
            directUrl = "http://attacker.lan:47190",
            refresh = null,
        )
        val paired = directOnly.copy(refresh = "newly-stored-refresh")
        var snapshotReads = 0
        val refreshBodies = mutableListOf<String>()
        val client = hostAwareClient(
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/host/token" -> {
                        refreshBodies += (req.body as OutgoingContent.ByteArrayContent)
                            .bytes().decodeToString()
                        respond(
                            """{"token":"stolen","expires_in":300}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    "/workspaces" -> respond(
                        """{"detail":"unauthorized"}""",
                        HttpStatusCode.Unauthorized,
                        jsonHdr,
                    )
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = {
                snapshotReads += 1
                listOf(if (snapshotReads == 1) directOnly else paired)
            },
        )

        val result = runCatching {
            SpecApi(client.client).listWorkspaces(directOnly.directUrl!!, null)
        }

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), refreshBodies)
        assertEquals(1, snapshotReads)
    }

    @Test fun a_refused_snapshot_credential_retries_a_current_safe_route() = runTest {
        val stale = host.copy(refresh = "rotated-out")
        val current = host.copy(refresh = "current")
        var snapshotReads = 0
        val exchanges = mutableListOf<String>()
        val client = hostAwareClient(
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/host/token" -> {
                        val body = (req.body as OutgoingContent.ByteArrayContent)
                            .bytes().decodeToString()
                        val credential = body.substringAfter("\"refresh\":\"").substringBefore('"')
                        exchanges += credential
                        if (credential == stale.refresh) {
                            respond(
                                """{"detail":"unauthorized"}""",
                                HttpStatusCode.Unauthorized,
                                jsonHdr,
                            )
                        } else {
                            respond(
                                """{"token":"current-bearer","expires_in":300}""",
                                HttpStatusCode.OK,
                                jsonHdr,
                            )
                        }
                    }
                    "/workspaces" -> respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = {
                snapshotReads += 1
                listOf(if (snapshotReads == 1) stale else current)
            },
        )

        val workspaces = SpecApi(client.client).listWorkspaces(stale.publicUrl, null)

        assertEquals(listOf("ws-1", "ws-2"), workspaces.map { it.id })
        assertEquals(listOf("rotated-out", "current"), exchanges)
    }

    @Test fun a_refused_snapshot_route_retries_the_current_host_route() = runTest {
        val stale = host.copy(
            publicUrl = "https://old.relay.example.com",
            refresh = "old-refresh",
        )
        val current = stale.copy(
            publicUrl = "https://new.relay.example.com",
            refresh = "new-refresh",
        )
        var snapshotReads = 0
        val exchanges = mutableListOf<Pair<String, String>>()
        val urls = mutableListOf<String>()
        val client = hostAwareClient(
            engine = MockEngine { req ->
                urls += req.url.toString()
                when (req.url.encodedPath) {
                    "/host/token" -> {
                        val body = (req.body as OutgoingContent.ByteArrayContent)
                            .bytes().decodeToString()
                        val credential = Regex(""""refresh":"([^"]+)"""")
                            .find(body)!!.groupValues[1]
                        exchanges += req.url.host to credential
                        if (req.url.host == "old.relay.example.com") {
                            respond(
                                """{"detail":"unauthorized"}""",
                                HttpStatusCode.Unauthorized,
                                jsonHdr,
                            )
                        } else {
                            respond(
                                """{"token":"current-bearer","expires_in":300}""",
                                HttpStatusCode.OK,
                                jsonHdr,
                            )
                        }
                    }
                    "/workspaces" -> respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = {
                snapshotReads += 1
                listOf(if (snapshotReads == 1) stale else current)
            },
        )

        val workspaces = SpecApi(client.client).listWorkspaces(stale.publicUrl, null)

        assertEquals(listOf("ws-1", "ws-2"), workspaces.map { it.id })
        assertEquals(
            listOf(
                "old.relay.example.com" to "old-refresh",
                "new.relay.example.com" to "new-refresh",
            ),
            exchanges,
        )
        assertEquals(
            listOf(
                "https://old.relay.example.com/host/token",
                "https://new.relay.example.com/host/token",
                "https://new.relay.example.com/workspaces",
            ),
            urls,
        )
    }

    @Test fun replacing_an_account_credential_invalidates_its_cached_bearer() = runTest {
        var current = host.copy(refresh = "old-refresh")
        val exchanges = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/host/token" -> {
                        val body = (req.body as OutgoingContent.ByteArrayContent)
                            .bytes().decodeToString()
                        val credential = Regex(""""refresh":"([^"]+)"""")
                            .find(body)!!.groupValues[1]
                        exchanges += credential
                        respond(
                            """{"token":"$credential-bearer","expires_in":300}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    "/workspaces" -> {
                        authorizations += req.headers[HttpHeaders.Authorization]
                        respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(current) },
        )
        val api = SpecApi(client.client)

        api.listWorkspaces(current.publicUrl!!, null)
        current = current.copy(refresh = "new-refresh")
        api.listWorkspaces(current.publicUrl!!, null)

        assertEquals(listOf("old-refresh", "new-refresh"), exchanges)
        assertEquals(
            listOf("Bearer old-refresh-bearer", "Bearer new-refresh-bearer"),
            authorizations,
        )
    }

    @Test fun restored_direct_authorization_is_replaced_on_a_relay_route() = runTest {
        val directBase = "http://direct.example"
        val publicBase = "https://public.relay.example"
        val routedHost = HostConnection(
            hostId = "host-stable",
            publicUrl = publicBase,
            refresh = "relay-refresh",
            directUrl = directBase,
        )
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                urls += request.url.toString()
                when (request.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"relay-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces/ws-1/threads/thread-1/seen" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(routedHost) },
        )
        val restored = WorkspaceConnection(
            id = "restored",
            baseUrl = "$directBase/workspaces/ws-1",
            token = "direct-token",
            hostId = routedHost.hostId,
            workspaceId = "ws-1",
            directToken = "direct-token",
        )

        SpecApi(client.client).markThreadSeen(restored, "thread-1", null)

        assertEquals(
            listOf(
                "$publicBase/host/token",
                "$publicBase/workspaces/ws-1/threads/thread-1/seen",
            ),
            urls,
        )
        assertEquals(listOf("Bearer relay-bearer"), authorizations)
    }

    @Test fun direct_only_routes_keep_their_standing_authorization() = runTest {
        val directBase = "http://direct.example"
        val directHost = HostConnection(
            hostId = "host-stable",
            directUrl = directBase,
        )
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                authorizations += request.headers[HttpHeaders.Authorization]
                respond("{}", HttpStatusCode.OK, jsonHdr)
            },
            hosts = { listOf(directHost) },
        )
        val direct = WorkspaceConnection(
            id = "direct",
            baseUrl = "$directBase/workspaces/ws-1",
            token = "direct-token",
            hostId = directHost.hostId,
            workspaceId = "ws-1",
            directToken = "direct-token",
        )

        SpecApi(client.client).markThreadSeen(direct, "thread-1", null)

        assertEquals(listOf("Bearer direct-token"), authorizations)
    }

    @Test fun workspace_route_identity_disambiguates_hosts_sharing_a_base() = runTest {
        val sharedBase = "https://contended.relay.example.com"
        val first = host.copy(hostId = "host-a", publicUrl = sharedBase, refresh = "refresh-a")
        val second = host.copy(hostId = "host-b", publicUrl = sharedBase, refresh = "refresh-b")
        val exchanges = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val contacts = mutableListOf<Pair<String, String>>()
        val client = hostAwareClient(
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/host/token" -> {
                        val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val credential = body.substringAfter("\"refresh\":\"").substringBefore('"')
                        exchanges += credential
                        respond(
                            """{"token":"bearer-${credential.removePrefix("refresh-")}","expires_in":300}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    "/workspaces/ws-1/threads/thread-1/seen" -> {
                        authorizations += req.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(first, second) },
            onHostContact = { hostId, base -> contacts += hostId to base },
        )
        val connection = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "$sharedBase/workspaces/ws-1",
            hostId = second.hostId,
            workspaceId = "ws-1",
        )

        SpecApi(client.client).markThreadSeen(connection, "thread-1", null)

        assertEquals(listOf("refresh-b"), exchanges)
        assertEquals(listOf("Bearer bearer-b"), authorizations)
        assertEquals(listOf(second.hostId to sharedBase), contacts)
    }

    @Test fun a_legacy_url_host_handle_routes_by_its_unambiguous_base() = runTest {
        val base = "https://host.relay.example.com"
        val current = host.copy(
            hostId = "host-stable",
            publicUrl = base,
            refresh = "refresh-current",
        )
        val exchanges = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/host/token" -> {
                        exchanges += (request.body as OutgoingContent.ByteArrayContent)
                            .bytes()
                            .decodeToString()
                        respond(
                            """{"token":"current-bearer","expires_in":300}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    "/workspaces/ws-1/threads/thread-1/seen" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(current) },
        )
        val legacy = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "$base/workspaces/ws-1",
            hostId = base,
            workspaceId = "ws-1",
        )

        SpecApi(client.client).markThreadSeen(legacy, "thread-1", null)

        assertTrue(exchanges.single().contains("refresh-current"))
        assertEquals(listOf("Bearer current-bearer"), authorizations)
    }

    @Test fun stable_host_id_never_falls_back_to_a_different_hosts_matching_base() = runTest {
        val base = "https://other.relay.example.com"
        val other = host.copy(
            hostId = "host-other",
            publicUrl = base,
            refresh = "other-refresh",
        )
        val exchanges = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/host/token" -> {
                        exchanges += (request.body as OutgoingContent.ByteArrayContent)
                            .bytes()
                            .decodeToString()
                        respond(
                            """{"token":"other-bearer","expires_in":300}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    "/workspaces/ws-1/threads/thread-1/seen" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(other) },
        )
        val stale = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "$base/workspaces/ws-1",
            hostId = "host-missing",
            workspaceId = "ws-1",
        )

        SpecApi(client.client).markThreadSeen(stale, "thread-1", null)

        assertTrue(exchanges.isEmpty())
        assertEquals(listOf<String?>(null), authorizations)
    }

    @Test fun a_legacy_url_host_handle_follows_a_recorded_public_url_rotation() = runTest {
        val oldBase = "https://old.relay.example.com"
        val current = host.copy(
            hostId = "host-stable",
            publicUrl = "https://new.relay.example.com",
            refresh = "refresh-current",
            legacyPublicUrls = listOf(oldBase),
        )
        val urls = mutableListOf<String>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                urls += request.url.toString()
                when (request.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"current-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces/ws-1/threads/thread-1/seen" ->
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(current) },
        )
        val legacy = WorkspaceConnection(
            id = "ws-1",
            baseUrl = "$oldBase/workspaces/ws-1",
            hostId = oldBase,
            workspaceId = "ws-1",
        )

        SpecApi(client.client).markThreadSeen(legacy, "thread-1", null)

        assertEquals(
            listOf(
                "https://new.relay.example.com/host/token",
                "https://new.relay.example.com/workspaces/ws-1/threads/thread-1/seen",
            ),
            urls,
        )
    }

    @Test fun a_legacy_workspace_suffix_routes_before_identity_migration() = runTest {
        val oldBase = "https://old.relay.example.com"
        val currentBase = "https://new.relay.example.com"
        val current = host.copy(
            hostId = "host-stable",
            publicUrl = currentBase,
            refresh = "refresh-current",
            legacyPublicUrls = listOf(oldBase),
        )
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                urls += request.url.toString()
                when (request.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"relay-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces/ws-1/threads/thread-1/seen" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(current) },
        )
        val legacy = WorkspaceConnection(
            id = "legacy-row",
            baseUrl = "$oldBase/workspaces/ws-1",
            token = "old-standing-token",
            hostId = oldBase,
            workspaceId = null,
        )

        SpecApi(client.client).markThreadSeen(legacy, "thread-1", null)

        assertEquals(
            listOf(
                "$currentBase/host/token",
                "$currentBase/workspaces/ws-1/threads/thread-1/seen",
            ),
            urls,
        )
        assertEquals(listOf("Bearer relay-bearer"), authorizations)
    }

    @Test fun uppercase_url_identity_routes_without_changing_path_case() = runTest {
        val legacyBase = "HTTPS://OLD.RELAY.EXAMPLE/CaseSensitive"
        val currentBase = "https://new.relay.example/CaseSensitive"
        val current = host.copy(
            hostId = "host-stable",
            publicUrl = currentBase,
            refresh = "refresh-current",
            legacyPublicUrls = listOf("https://old.relay.example/CaseSensitive"),
        )
        val urls = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                urls += request.url.toString()
                when (request.url.encodedPath) {
                    "/CaseSensitive/host/token" -> respond(
                        """{"token":"current-bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/CaseSensitive/workspaces" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    }
                    "/CaseSensitive/workspaces/ws-2/threads/thread-1/seen" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond("{}", HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(current) },
        )
        val legacy = WorkspaceConnection(
            id = "legacy-row",
            baseUrl = "$legacyBase/workspaces/ws-2",
            hostId = legacyBase,
            workspaceId = "ws-2",
        )
        val api = SpecApi(client.client)

        val identities = verifyLegacyIdentities(api, listOf(legacy), listOf(current)).identities
        api.markThreadSeen(legacy, "thread-1", null)

        assertEquals(
            listOf(VerifiedIdentity("legacy-row", "host-stable", "ws-2")),
            identities,
        )
        assertEquals(
            listOf(
                "$currentBase/host/token",
                "$currentBase/workspaces",
                "$currentBase/workspaces/ws-2/threads/thread-1/seen",
            ),
            urls,
        )
        assertEquals(
            listOf("Bearer current-bearer", "Bearer current-bearer"),
            authorizations,
        )
    }

    @Test fun an_unscoped_route_does_not_choose_between_normalized_equivalent_bases() = runTest {
        val sharedBase = "https://contended.relay.example.com"
        val first = host.copy(hostId = "host-a", publicUrl = sharedBase, refresh = "refresh-a")
        val second = host.copy(
            hostId = "host-b",
            publicUrl = "HTTPS://CONTENDED.RELAY.EXAMPLE.COM",
            refresh = "refresh-b",
        )
        val exchanges = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/host/token" -> {
                        exchanges += (request.body as OutgoingContent.ByteArrayContent)
                            .bytes()
                            .decodeToString()
                        respond(
                            """{"token":"bearer","expires_in":300}""",
                            HttpStatusCode.OK,
                            jsonHdr,
                        )
                    }
                    "/workspaces" -> {
                        authorizations += request.headers[HttpHeaders.Authorization]
                        respond(listPayload, HttpStatusCode.OK, jsonHdr)
                    }
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(first, second) },
        )

        SpecApi(client.client).listWorkspaces(sharedBase, null)

        assertTrue(exchanges.isEmpty())
        assertEquals(listOf<String?>(null), authorizations)
    }

    @Test fun explicit_route_prefers_the_first_raw_base_within_one_normalized_host_identity() = runTest {
        val preferred = "HTTPS://SAME.RELAY.EXAMPLE/CaseSensitive"
        val equivalent = "https://same.relay.example/CaseSensitive"
        val known = HostConnection(
            hostId = "host-a",
            directUrl = preferred,
            publicUrl = equivalent,
        )
        val attempted = mutableListOf<String>()
        val api = SpecApi(
            HttpClient(
                MockEngine { request ->
                    attempted += request.url.toString()
                    respond(listPayload, HttpStatusCode.OK, jsonHdr)
                },
            ) { mshipDefaults() },
        )

        val (reachedBase, workspaces) = reachableHostWorkspaces(
            api = api,
            requestedBase = equivalent,
            token = null,
            hosts = listOf(known),
        )

        assertEquals(preferred, reachedBase)
        assertEquals(listOf("ws-1", "ws-2"), workspaces.map { it.id })
        assertEquals(1, attempted.size)
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
        val withLan = host.copy(
            refresh = null,
            directUrl = "http://192.168.1.9:47190",
        )
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
            onHostContact = { _, base -> contacts += base },
        )

        assertTrue(refreshHostWorkspaceConnections(SpecApi(client.client), host) != null)
        assertEquals(emptyList<String>(), contacts)
    }

    @Test fun fleet_workspace_probe_does_not_fail_over_after_authentication_rejection() = runTest {
        val attempted = mutableListOf<String>()
        val probeHost = HostConnection(
            hostId = "host-a",
            directUrl = "http://direct.example",
            publicUrl = "https://public.example",
        )
        val api = SpecApi(
            HttpClient(
                MockEngine { request ->
                    attempted += request.url.host
                    respond(
                        if (request.url.host == "direct.example") "unauthorized" else "[]",
                        if (request.url.host == "direct.example") {
                            HttpStatusCode.Unauthorized
                        } else {
                            HttpStatusCode.OK
                        },
                        jsonHdr,
                    )
                },
            ) { mshipDefaults() },
        )

        val error = runCatching { reachableHostWorkspaces(api, probeHost) }.exceptionOrNull()

        assertTrue(error is AuthException)
        assertEquals(listOf("direct.example"), attempted)
    }

    @Test fun settings_discovery_returns_the_known_host_base_that_answered() = runTest {
        val direct = "http://direct.example"
        val public = "https://public.example"
        val known = HostConnection(
            hostId = "host-a",
            directUrl = direct,
            publicUrl = public,
        )
        val attempted = mutableListOf<String>()
        val api = SpecApi(
            HttpClient(
                MockEngine { request ->
                    attempted += request.url.host
                    if (request.url.host == "direct.example") {
                        throw java.io.IOException("direct unavailable")
                    }
                    respond(listPayload, HttpStatusCode.OK, jsonHdr)
                },
            ) { mshipDefaults() },
        )

        val (reachedBase, workspaces) = reachableHostWorkspaces(
            api = api,
            requestedBase = direct,
            token = null,
            hosts = listOf(known),
        )

        assertEquals(public, reachedBase)
        assertEquals(listOf("ws-1", "ws-2"), workspaces.map { it.id })
        assertEquals(listOf("direct.example", "public.example"), attempted)
    }

    @Test fun settings_discovery_routes_a_unique_legacy_public_alias_to_the_current_base() = runTest {
        val legacy = "HTTPS://OLD.RELAY.EXAMPLE/CaseSensitive"
        val current = "https://current.relay.example/CaseSensitive"
        val known = HostConnection(
            hostId = "host-a",
            publicUrl = current,
            legacyPublicUrls = listOf("https://old.relay.example/CaseSensitive"),
        )
        val attempted = mutableListOf<String>()
        val api = SpecApi(
            HttpClient(
                MockEngine { request ->
                    attempted += request.url.toString()
                    respond(listPayload, HttpStatusCode.OK, jsonHdr)
                },
            ) { mshipDefaults() },
        )

        val (reachedBase, workspaces) = reachableHostWorkspaces(
            api = api,
            requestedBase = legacy,
            token = null,
            hosts = listOf(known),
        )

        assertEquals(current, reachedBase)
        assertEquals(listOf("ws-1", "ws-2"), workspaces.map { it.id })
        assertEquals(listOf("$current/workspaces"), attempted)
    }

    @Test fun settings_discovery_refuses_an_ambiguous_legacy_public_alias() = runTest {
        val legacy = "https://old.relay.example"
        val hosts = listOf(
            HostConnection(
                hostId = "host-a",
                publicUrl = "https://a.relay.example",
                legacyPublicUrls = listOf(legacy),
            ),
            HostConnection(
                hostId = "host-b",
                publicUrl = "https://b.relay.example",
                legacyPublicUrls = listOf("HTTPS://OLD.RELAY.EXAMPLE"),
            ),
        )
        var attempts = 0
        val api = SpecApi(
            HttpClient(
                MockEngine {
                    attempts++
                    respond(listPayload, HttpStatusCode.OK, jsonHdr)
                },
            ) { mshipDefaults() },
        )

        val error = runCatching {
            reachableHostWorkspaces(
                api = api,
                requestedBase = legacy,
                token = null,
                hosts = hosts,
            )
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertEquals(0, attempts)
    }

    @Test fun uppercase_known_host_direct_failure_reaches_its_public_base() = runTest {
        val direct = "HTTP://DIRECT.EXAMPLE/CaseSensitive"
        val requestedDirect = "http://direct.example/CaseSensitive"
        val public = "https://public.example/CaseSensitive"
        val known = HostConnection(
            hostId = "host-a",
            directUrl = direct,
            publicUrl = public,
        )
        val attempted = mutableListOf<String>()
        val api = SpecApi(
            HttpClient(
                MockEngine { request ->
                    attempted += request.url.host.lowercase()
                    if (request.url.host.equals("direct.example", ignoreCase = true)) {
                        throw java.io.IOException("direct unavailable")
                    }
                    respond(listPayload, HttpStatusCode.OK, jsonHdr)
                },
            ) { mshipDefaults() },
        )

        val (reachedBase, workspaces) = reachableHostWorkspaces(
            api = api,
            requestedBase = requestedDirect,
            token = null,
            hosts = listOf(known),
        )

        assertEquals(public, reachedBase)
        assertEquals(listOf("ws-1", "ws-2"), workspaces.map { it.id })
        assertEquals(
            listOf("direct.example", "public.example"),
            attempted,
        )
    }

    @Test fun relay_replacement_migrates_an_authenticated_direct_root_end_to_end() = runTest {
        val oldPublic = "https://old.relay.example"
        val direct = "http://direct.example"
        val replacement = RelayAccount("new.example", "new-fleet-token")
        val oldHost = HostConnection(
            hostId = "host-a",
            publicUrl = oldPublic,
            refresh = "old-refresh",
            directUrl = direct,
            relayDomain = "relay.example",
        )
        val fleet = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example", "old-fleet-token"),
            replacement = replacement,
            hosts = listOf(oldHost),
            connections = listOf(
                WorkspaceConnection(
                    id = "legacy-root",
                    baseUrl = oldPublic,
                    hostId = oldHost.hostId,
                    directToken = "direct-token",
                ),
            ),
        )
        val authorizations = mutableListOf<String?>()
        val urls = mutableListOf<String>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                urls += request.url.toString()
                authorizations += request.headers[HttpHeaders.Authorization]
                respond(
                    """{"workspaces":[{"id":"ws-1","name":"alpha","state":"healthy"}]}""",
                    HttpStatusCode.OK,
                    jsonHdr,
                )
            },
            hosts = { fleet.hosts },
        )

        val restored = fleet.connections.single()
        val identities = verifyLegacyIdentities(
            SpecApi(client.client),
            listOf(restored),
            fleet.hosts,
        ).identities
        val target = fleetWorkspaceRefreshTargets(
            hosts = fleet.hosts,
            connections = fleet.connections,
            account = replacement,
            identities = identities,
        ).single()
        val refreshed = refreshHostWorkspaceConnections(
            SpecApi(client.client),
            target.host,
            identities,
            directToken = target.directToken,
        )!!
        val migrated = replaceHostConnections(
            existing = fleet.connections,
            hostId = oldHost.hostId,
            discovered = refreshed.connections,
            identities = refreshed.identities,
            hosts = fleet.hosts,
            activatePriorDirectToken = true,
        ).single()

        assertEquals(direct, restored.baseUrl)
        assertNull(restored.workspaceId)
        assertEquals("direct-token", restored.token)
        assertEquals(
            listOf("$direct/workspaces", "$direct/workspaces"),
            urls,
        )
        assertEquals(
            listOf("Bearer direct-token", "Bearer direct-token"),
            authorizations,
        )
        assertEquals(
            listOf(VerifiedIdentity("legacy-root", oldHost.hostId, "ws-1")),
            identities,
        )
        assertEquals("legacy-root", migrated.id)
        assertEquals("ws-1", migrated.workspaceId)
        assertEquals("$direct/workspaces/ws-1", migrated.baseUrl)
        assertEquals("direct-token", migrated.token)
        assertEquals("direct-token", migrated.directToken)
    }

    @Test fun transient_root_verification_failure_is_retained_and_retried() = runTest {
        val currentBase = "https://public.example"
        val known = HostConnection(hostId = "host-a", publicUrl = currentBase)
        val singletonPayload =
            """{"workspaces":[{"id":"ws-1","name":"alpha","state":"healthy"}]}"""
        var workspaceCalls = 0
        val api = SpecApi(
            HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath != "/workspaces") {
                        return@MockEngine respond(
                            "not found",
                            HttpStatusCode.NotFound,
                            jsonHdr,
                        )
                    }
                    workspaceCalls += 1
                    if (workspaceCalls == 1) {
                        throw java.io.IOException("transient verification failure")
                    }
                    respond(singletonPayload, HttpStatusCode.OK, jsonHdr)
                },
            ) { mshipDefaults() },
        )
        val root = WorkspaceConnection(
            id = "legacy-root",
            baseUrl = currentBase,
            hostId = known.hostId,
            workspaceId = null,
        )

        val firstVerification = verifyLegacyIdentities(api, listOf(root), listOf(known))
        val firstRefresh = refreshHostWorkspaceConnections(
            api,
            known,
            firstVerification.identities,
        )!!
        val firstReconciliation = replaceHostConnections(
            existing = listOf(root),
            hostId = known.hostId,
            discovered = firstRefresh.connections,
            identities = firstRefresh.identities,
        )

        assertEquals(emptyList<VerifiedIdentity>(), firstVerification.identities)
        assertEquals(
            setOf("legacy-root", firstRefresh.connections.single().id),
            firstReconciliation.map { it.id }.toSet(),
        )

        val secondVerification = verifyLegacyIdentities(
            api,
            unresolvedLegacyConnections(firstReconciliation),
            listOf(known),
        )
        val secondRefresh = refreshHostWorkspaceConnections(
            api,
            known,
            secondVerification.identities,
        )!!
        val recovered = replaceHostConnections(
            existing = firstReconciliation,
            hostId = known.hostId,
            discovered = secondRefresh.connections,
            identities = secondRefresh.identities,
        )

        assertEquals(
            listOf(VerifiedIdentity("legacy-root", known.hostId, "ws-1")),
            secondVerification.identities,
        )
        assertEquals(4, workspaceCalls)
        assertEquals(1, recovered.size)
        assertEquals("legacy-root", recovered.single().id)
        assertEquals("ws-1", recovered.single().workspaceId)
    }

    @Test fun requested_root_is_verified_and_adopted_after_public_fallback() = runTest {
        val direct = "http://direct.example"
        val public = "https://public.example"
        val known = HostConnection(
            hostId = "host-a",
            directUrl = direct,
            publicUrl = public,
        )
        val client = hostAwareClient(
            engine = MockEngine { request ->
                if (request.url.host == "direct.example") {
                    throw java.io.IOException("direct unavailable")
                }
                when (request.url.encodedPath) {
                    "/health" -> respond(
                        """{"status":"ok","host_id":"host-a","workspace_count":1}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces" -> respond(
                        """{"workspaces":[{"id":"ws-1","name":"alpha","state":"healthy"}]}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(known) },
        )
        val api = SpecApi(client.client)
        val legacy = WorkspaceConnection("legacy", direct)
        val candidates = legacyConnectionsForDiscovery(
            connections = listOf(legacy),
            hostBases = listOf(direct, public),
            workspaceId = "ws-1",
        )
        val identities = verifyLegacyIdentities(api, candidates, listOf(known)).identities
        val discovered = deriveConnection(
            hostBase = public,
            hostToken = null,
            hostId = known.hostId,
            workspaceId = "ws-1",
            workspaceName = "alpha",
            state = "healthy",
        )

        val adopted = adoptManualConnections(
            existing = listOf(legacy),
            discovered = listOf(discovered),
            identities = identities,
        )

        assertEquals(1, adopted.size)
        assertEquals(legacy.id, adopted.single().id)
        assertEquals(known.hostId, adopted.single().hostId)
        assertEquals("ws-1", adopted.single().workspaceId)
        assertEquals("$public/workspaces/ws-1", adopted.single().baseUrl)
    }


    @Test fun a_re_pair_failure_is_an_authentication_failure() {
        val error: Exception = RePairNeededException("https://host.example.com")

        assertTrue(error is AuthException)
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
            listOf(badHost, goodHost),
        )

        assertTrue(verification.requiresRePair)
        assertEquals(listOf("good"), verification.identities.map { it.connectionId })
    }

    @Test fun unauthenticated_workspace_health_cannot_claim_a_fleet_identity() = runTest {
        val urls = mutableListOf<String>()
        val api = SpecApi(HttpClient(MockEngine { req ->
            urls += req.url.toString()
            respond(
                """{"status":"ok","workspace":"alpha","host_id":"host-a","workspace_id":"ws-1"}""",
                HttpStatusCode.OK,
                jsonHdr,
            )
        }) { mshipDefaults() })
        val legacy = WorkspaceConnection(
            id = "legacy-row",
            baseUrl = "http://malicious:47100/workspaces/ws-1",
            token = "standing",
            workspaceName = "alpha",
        )
        val fleetHost = HostConnection(
            hostId = "host-a",
            publicUrl = "https://real.relay.example",
            refresh = "refresh",
        )

        val identities = verifyLegacyIdentities(api, listOf(legacy), listOf(fleetHost)).identities

        assertEquals(emptyList<VerifiedIdentity>(), identities)
        assertEquals(emptyList<String>(), urls)
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
            workspaceId = null,
        )
        val knownHost = HostConnection(hostId = "host-a", publicUrl = "http://lan:47190")
        val identities = verifyLegacyIdentities(api, listOf(legacy), listOf(knownHost)).identities
        val refreshed = refreshHostWorkspaceConnections(
            api,
            knownHost,
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
                "http://lan:47190/workspaces",
                "http://lan:47190/workspaces",
            ),
            urls,
        )
    }

    @Test fun legacy_identity_verification_uses_the_hosts_current_fleet_route() = runTest {
        val currentBase = "https://new.relay.example"
        val oldBase = "https://old.relay.example"
        val urls = mutableListOf<String>()
        val api = SpecApi(HttpClient(MockEngine { request ->
            urls += request.url.toString()
            respond(listPayload, HttpStatusCode.OK, jsonHdr)
        }) { mshipDefaults() })
        val knownHost = HostConnection(
            hostId = "host-a",
            publicUrl = currentBase,
            legacyPublicUrls = listOf(oldBase),
        )
        val legacy = WorkspaceConnection(
            id = "legacy-row",
            baseUrl = "$oldBase/workspaces/ws-2",
            hostId = oldBase,
        )

        val identities = verifyLegacyIdentities(
            api,
            listOf(legacy),
            listOf(knownHost),
        ).identities

        assertEquals(listOf(VerifiedIdentity("legacy-row", "host-a", "ws-2")), identities)
        assertEquals(listOf("$currentBase/workspaces"), urls)
    }

    @Test fun ordinary_successful_host_traffic_advances_last_phone_contact() = runTest {
        var stored = listOf(host.copy(lastContactAtMillis = 10))
        val fx = HostFixture()
        val client = hostAwareClient(
            engine = fx.engine,
            hosts = { stored },
            onHostContact = { hostId, base ->
                stored = recordHostContact(stored, hostId, base, 20)
            },
        )

        SpecApi(client.client).listWorkspaces(host.hostBase(), null)

        assertEquals(20L, stored.single().lastContactAtMillis)
    }

    @Test fun malformed_success_body_does_not_advance_last_phone_contact() = runTest {
        val contacts = mutableListOf<Pair<String, String>>()
        val client = hostAwareClient(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/host/token" -> respond(
                        """{"token":"bearer","expires_in":300}""",
                        HttpStatusCode.OK,
                        jsonHdr,
                    )
                    "/workspaces" -> respond("not json", HttpStatusCode.OK, jsonHdr)
                    else -> respond("not found", HttpStatusCode.NotFound, jsonHdr)
                }
            },
            hosts = { listOf(host) },
            onHostContact = { hostId, base -> contacts += hostId to base },
        )

        val error = runCatching {
            SpecApi(client.client).listWorkspaces(host.hostBase(), null)
        }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals(emptyList<Pair<String, String>>(), contacts)
    }

    @Test fun out_of_order_contact_persistence_cannot_move_freshness_backwards() {
        val stored = listOf(host.copy(lastContactAtMillis = 20))

        val afterOlderWrite = recordHostContact(stored, host.hostId, host.hostBase(), 10)
        val afterNewerWrite = recordHostContact(afterOlderWrite, host.hostId, host.hostBase(), 30)

        assertEquals(20L, afterOlderWrite.single().lastContactAtMillis)
        assertEquals(30L, afterNewerWrite.single().lastContactAtMillis)
    }

    @Test fun contact_freshness_updates_only_the_contacted_host_when_bases_collide() {
        val sharedBase = "https://contended.relay.example.com"
        val first = host.copy(hostId = "host-a", publicUrl = sharedBase, lastContactAtMillis = null)
        val second = host.copy(hostId = "host-b", publicUrl = sharedBase, lastContactAtMillis = null)

        val updated = recordHostContact(listOf(first, second), second.hostId, sharedBase, 30)

        assertNull(updated.single { it.hostId == first.hostId }.lastContactAtMillis)
        assertEquals(30L, updated.single { it.hostId == second.hostId }.lastContactAtMillis)
    }

    @Test fun contact_persistence_failure_does_not_fail_a_successful_api_call() = runTest {
        val fx = HostFixture()
        val client = hostAwareClient(
            engine = fx.engine,
            hosts = { listOf(host) },
            onHostContact = { _, _ -> throw IllegalStateException("DataStore write failed") },
        )

        val workspaces = SpecApi(client.client).listWorkspaces(host.hostBase(), null)

        assertEquals(listOf("ws-1"), workspaces.map { it.id })
    }

    @Test fun contact_persistence_cancellation_still_cancels_the_api_call() = runTest {
        val fx = HostFixture()
        val client = hostAwareClient(
            engine = fx.engine,
            hosts = { listOf(host) },
            onHostContact = { _, _ -> throw CancellationException("cancelled") },
        )

        val error = runCatching {
            SpecApi(client.client).listWorkspaces(host.hostBase(), null)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }
}
