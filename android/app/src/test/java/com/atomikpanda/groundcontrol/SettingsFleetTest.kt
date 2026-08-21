package com.atomikpanda.groundcontrol

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.InvalidRelayDirectoryException
import com.atomikpanda.groundcontrol.data.LegacyIdentityVerification
import com.atomikpanda.groundcontrol.data.NotificationsSetting
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.RelayDirectoryTransformer
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.ui.settings.SettingsViewModel
import com.atomikpanda.groundcontrol.ui.settings.canAdoptDirectHostIdentity
import com.atomikpanda.groundcontrol.ui.settings.classifyFleetRefreshFailure
import com.atomikpanda.groundcontrol.ui.settings.directUrlForDiscovery
import com.atomikpanda.groundcontrol.ui.settings.observeRelayAccountChanges
import com.atomikpanda.groundcontrol.data.unresolvedLegacyConnections
import com.atomikpanda.groundcontrol.ui.settings.fleetWorkspaceRefreshTargets
import com.atomikpanda.groundcontrol.ui.settings.fleetWorkspaceRefreshTarget
import com.atomikpanda.groundcontrol.ui.settings.cacheFleetIdentityVerification
import com.atomikpanda.groundcontrol.data.legacyConnectionsForDiscovery
import com.atomikpanda.groundcontrol.data.routeOwnershipGenerationAfter
import com.atomikpanda.groundcontrol.ui.settings.selectedDiscoveryConnection
import com.atomikpanda.groundcontrol.ui.settings.verificationForGeneration
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.ui.settings.visibleSettingsResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFleetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val hostsKey = stringPreferencesKey("hosts")

    private fun dataStore(name: String, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile(name).also { check(it.delete()) }
        }

    private fun notifications() = object : NotificationsSetting {
        override val enabled = MutableStateFlow(false)
        override suspend fun set(value: Boolean) {
            enabled.value = value
        }
    }

    private fun viewModel(
        dataStore: DataStore<Preferences>,
        response: String,
        scope: CoroutineScope,
        transformer: RelayDirectoryTransformer = RelayDirectoryTransformer(),
    ) = SettingsViewModel(
        repo = ConnectionsRepository(dataStore),
        api = SpecApi(HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/hosts") {
                respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                throw IOException("host refresh intentionally unavailable in directory replacement tests")
            }
        }) { mshipDefaults() }),
        notifications = notifications(),
        hosts = HostsRepository(dataStore),
        transformer = transformer,
        testScope = scope,
    )

    @Test fun nested_transformation_failure_preserves_exact_cached_fleet() = runTest {
        val store = dataStore("nested-invalid.preferences_pb", backgroundScope)
        val repository = HostsRepository(store)
        val account = RelayAccount("relay.example.com", "fleet-token")
        val cached = HostConnection(
            hostId = "cached",
            publicUrl = "https://cached.relay.example.com/root",
            relayDomain = account.relayDomain,
            refresh = "cached-refresh",
        )
        repository.setRelayAccount(account)
        repository.upsert(cached)
        val emissions = mutableListOf<List<HostConnection>>()
        backgroundScope.launch { repository.hosts.collect { emissions += it } }
        runCurrent()
        val vm = viewModel(
            store,
            """{"hosts":[
                {"host_id":"new","public_url":"https://new.relay.example.com"},
                {"host_id":"broken","public_url":"https://broken.relay.example.com "}
            ]}""",
            backgroundScope,
        )

        vm.refreshFleetNow().join()
        runCurrent()

        assertEquals(listOf(cached), repository.snapshot())
        assertEquals(listOf(listOf(cached)), emissions.distinct())
        assertEquals(
            "Relay returned malformed host data — showing last known hosts",
            vm.testResult.value,
        )
    }

    @Test fun valid_authoritative_response_replaces_cache_in_one_observed_snapshot() = runTest {
        val store = dataStore("valid-directory.preferences_pb", backgroundScope)
        val repository = HostsRepository(store)
        val account = RelayAccount("relay.example.com", "fleet-token")
        val oldFleet = listOf(
            HostConnection(
                hostId = "old",
                publicUrl = "https://old.relay.example.com",
                relayDomain = account.relayDomain,
            ),
        )
        repository.setRelayAccount(account)
        oldFleet.forEach { host -> repository.upsert(host) }
        val emissions = mutableListOf<List<HostConnection>>()
        backgroundScope.launch { repository.hosts.collect { emissions += it } }
        runCurrent()
        val vm = viewModel(
            store,
            """{"hosts":[{"host_id":"new","state":"online","public_url":"https://new.relay.example.com/root/"}]}""",
            backgroundScope,
        )

        vm.refreshFleetNow().join()
        runCurrent()

        val expected = listOf(
            HostConnection(
                hostId = "new",
                publicUrl = "https://new.relay.example.com/root",
                state = "online",
                relayDomain = account.relayDomain,
            ),
        )
        assertEquals(expected, repository.snapshot())
        assertEquals(listOf(oldFleet, expected), emissions.distinct())
    }

    @Test fun active_hosts_refresh_with_pending_placeholders_that_have_no_route() = runTest {
        val store = dataStore("active-and-pending.preferences_pb", backgroundScope)
        val repository = HostsRepository(store)
        val account = RelayAccount("relay.example.com", "fleet-token")
        repository.setRelayAccount(account)
        repository.upsert(
            HostConnection(
                hostId = "old",
                publicUrl = "https://old.relay.example.com",
                relayDomain = account.relayDomain,
            ),
        )
        val vm = viewModel(
            store,
            """{"hosts":[
                {"host_id":"active","state":"online","public_url":"https://active.relay.example.com"},
                {"state":"pending-approval","request_id":"request-1"}
            ]}""",
            backgroundScope,
        )

        vm.refreshFleetNow().join()
        runCurrent()

        assertEquals(
            listOf(
                HostConnection(
                    hostId = "active",
                    publicUrl = "https://active.relay.example.com",
                    state = "online",
                    relayDomain = account.relayDomain,
                ),
                HostConnection(
                    hostId = "pending:request-1",
                    publicUrl = "",
                    state = "pending-approval",
                    relayDomain = account.relayDomain,
                    requestId = "request-1",
                ),
            ),
            repository.snapshot(),
        )
        assertEquals("Fleet: 2 host(s)", vm.testResult.value)
    }

    @Test fun invalid_refresh_keeps_preexisting_cache_bytes_without_rewrite() = runTest {
        val store = dataStore("legacy-cache.preferences_pb", backgroundScope)
        val repository = HostsRepository(store)
        val account = RelayAccount("relay.example.com", "fleet-token")
        val encoded = """[{"hostId":"legacy","publicUrl":"https://legacy.relay.example/root/","relayDomain":"relay.example.com"}]"""
        repository.setRelayAccount(account)
        store.edit { it[hostsKey] = encoded }
        val vm = viewModel(
            store,
            """{"hosts":[{"host_id":"bad","public_url":"ftp://bad.relay.example"}]}""",
            backgroundScope,
        )

        vm.refreshFleetNow().join()
        runCurrent()

        assertEquals(encoded, store.data.first()[hostsKey])
        assertEquals("https://legacy.relay.example/root/", repository.snapshot().single().publicUrl)
    }

    @Test fun absent_or_null_hosts_response_preserves_cached_fleet_without_rewrite() = runTest {
        for ((name, payload) in listOf(
            "missing" to "{}",
            "null" to """{"hosts":null}""",
        )) {
            val store = dataStore("$name-hosts.preferences_pb", backgroundScope)
            val repository = HostsRepository(store)
            val account = RelayAccount("relay.example.com", "fleet-token")
            val encoded = """[{"hostId":"legacy-$name","publicUrl":"https://legacy.relay.example/root/","relayDomain":"relay.example.com"}]"""
            repository.setRelayAccount(account)
            store.edit { it[hostsKey] = encoded }
            val vm = viewModel(store, payload, backgroundScope)

            vm.refreshFleetNow().join()
            runCurrent()

            assertEquals(encoded, store.data.first()[hostsKey])
            assertEquals(
                "Relay returned malformed host data — showing last known hosts",
                vm.testResult.value,
            )
        }
    }

    @Test fun real_refresh_persists_a_transformer_sentinel_without_recanonicalizing_it() = runTest {
        val store = dataStore("sentinel-directory.preferences_pb", backgroundScope)
        val repository = HostsRepository(store)
        val account = RelayAccount("relay.example.com", "fleet-token")
        repository.setRelayAccount(account)
        repository.upsert(
            HostConnection(
                hostId = "old",
                publicUrl = "https://old.relay.example",
                relayDomain = account.relayDomain,
            ),
        )
        val calls = mutableListOf<String>()
        val vm = viewModel(
            store,
            """{"hosts":[{"host_id":"sentinel","public_url":"https://wire.relay.example/root"}]}""",
            backgroundScope,
            RelayDirectoryTransformer { route ->
                calls += route
                "HTTPS://SENTINEL.RELAY.EXAMPLE/Root"
            },
        )

        vm.refreshFleetNow().join()
        runCurrent()

        assertEquals(listOf("https://wire.relay.example/root"), calls)
        assertEquals(
            "HTTPS://SENTINEL.RELAY.EXAMPLE/Root",
            repository.snapshot().single().publicUrl,
        )
    }
    @Test fun an_external_relay_account_update_refreshes_an_existing_settings_screen() = runTest {
        val accounts = MutableStateFlow<RelayAccount?>(null)
        var refreshes = 0
        val collecting = backgroundScope.launch {
            observeRelayAccountChanges(
                accounts = accounts,
                hasHostsForAccount = { false },
                refreshFleet = { refreshes += 1 },
            )
        }
        runCurrent()

        val account = RelayAccount("relay.example", "fleet-token")
        accounts.value = account
        runCurrent()
        accounts.value = account
        runCurrent()

        assertEquals(1, refreshes)
        collecting.cancel()
    }

    @Test fun an_existing_fleet_only_refreshes_when_the_account_changes() = runTest {
        val accounts = MutableStateFlow<RelayAccount?>(RelayAccount("old.example", "old"))
        var refreshes = 0
        val collecting = backgroundScope.launch {
            observeRelayAccountChanges(
                accounts = accounts,
                hasHostsForAccount = { true },
                refreshFleet = { refreshes += 1 },
            )
        }
        runCurrent()
        assertEquals(0, refreshes)

        accounts.value = RelayAccount("new.example", "new")
        runCurrent()

        assertEquals(1, refreshes)
        collecting.cancel()
    }

    @Test fun an_initial_relay_account_refreshes_when_only_direct_hosts_exist() = runTest {
        val account = RelayAccount("relay.example", "fleet-token")
        val accounts = MutableStateFlow<RelayAccount?>(account)
        var inspected: RelayAccount? = null
        var refreshes = 0
        val collecting = backgroundScope.launch {
            observeRelayAccountChanges(
                accounts = accounts,
                hasHostsForAccount = {
                    inspected = it
                    false
                },
                refreshFleet = { refreshes += 1 },
            )
        }

        runCurrent()

        assertEquals(account, inspected)
        assertEquals(1, refreshes)
        collecting.cancel()
    }

    @Test fun fleet_refresh_includes_relay_and_owned_direct_generations() {
        val account = RelayAccount("new.example", "new-token")
        val relayHost = HostConnection(
            hostId = "new-host",
            refresh = "relay-refresh",
            relayDomain = account.relayDomain,
        )
        val retainedDirect = HostConnection(
            hostId = "retained-host",
            directUrl = "http://direct.example",
            relayDomain = null,
        )
        val unrelatedDirect = HostConnection(
            hostId = "unrelated-host",
            directUrl = "http://other.example",
            relayDomain = null,
        )
        val stableDirectConnection = WorkspaceConnection(
            id = "stable-workspace",
            baseUrl = "${retainedDirect.directUrl}/workspaces/ws-1",
            token = "direct-token",
            hostId = retainedDirect.hostId,
            workspaceId = "ws-1",
        )

        val selected = fleetWorkspaceRefreshTargets(
            hosts = listOf(relayHost, retainedDirect, unrelatedDirect),
            connections = listOf(stableDirectConnection),
            account = account,
            identities = emptyList(),
            routeOwnershipGeneration = 41L,
        )

        assertEquals(listOf(relayHost, retainedDirect), selected.map { it.host })
        assertEquals(listOf(null, "direct-token"), selected.map { it.directToken })
        assertEquals(listOf(41L, 41L), selected.map { it.expectedSourceGeneration.routeOwnershipGeneration })
        assertEquals(
            listOf(emptyList(), listOf(stableDirectConnection)),
            selected.map { it.expectedSourceGeneration.sources },
        )
    }

    @Test fun second_host_gets_a_fresh_target_after_first_host_changes_connections() {
        val account = RelayAccount("relay.example", "fleet-token")
        val first = HostConnection(
            hostId = "host-a",
            publicUrl = "https://a.relay.example",
            refresh = "refresh-a",
            relayDomain = account.relayDomain,
        )
        val second = HostConnection(
            hostId = "host-b",
            publicUrl = "https://b.relay.example",
            refresh = "refresh-b",
            relayDomain = account.relayDomain,
        )
        val hosts = listOf(first, second)
        val captured = 50L
        val firstTarget = fleetWorkspaceRefreshTarget(
            host = first,
            hosts = hosts,
            connections = emptyList(),
            account = account,
            identities = emptyList(),
            routeOwnershipGeneration = captured,
        )!!
        val firstConnection = WorkspaceConnection(
            id = "workspace-a",
            baseUrl = "${first.publicUrl}/workspaces/ws",
            hostId = first.hostId,
            workspaceId = "ws",
        )
        val afterFirst = routeOwnershipGenerationAfter(
            current = captured,
            previousAccount = account,
            previousHosts = hosts,
            previousConnections = emptyList(),
            updatedAccount = account,
            updatedHosts = hosts,
            updatedConnections = listOf(firstConnection),
        )

        val secondTarget = fleetWorkspaceRefreshTarget(
            host = second,
            hosts = hosts,
            connections = listOf(firstConnection),
            account = account,
            identities = emptyList(),
            routeOwnershipGeneration = afterFirst,
        )!!

        assertEquals(first.hostId, firstTarget.host.hostId)
        assertEquals(captured + 1, secondTarget.expectedSourceGeneration.routeOwnershipGeneration)
    }

    @Test fun route_change_during_identity_verification_is_rejected() {
        val verification = LegacyIdentityVerification(emptyList(), requiresRePair = false)

        assertNull(
            cacheFleetIdentityVerification(
                expectedGeneration = 60L,
                currentGeneration = 61L,
                verification = verification,
            ),
        )
    }

    @Test fun first_host_adoption_invalidates_cached_identity_before_second_host() {
        val account = RelayAccount("relay.example", "fleet-token")
        val first = HostConnection(
            hostId = "host-a",
            publicUrl = "https://a.relay.example",
            refresh = "refresh-a",
            relayDomain = account.relayDomain,
        )
        val second = HostConnection(
            hostId = "host-b",
            publicUrl = "https://b.relay.example",
            refresh = "refresh-b",
            relayDomain = account.relayDomain,
        )
        val legacy = WorkspaceConnection(
            id = "legacy",
            baseUrl = "${second.publicUrl}/workspaces/ws",
        )
        val staleIdentity = VerifiedIdentity(legacy.id, second.hostId, "ws")
        val verification = LegacyIdentityVerification(
            identities = listOf(staleIdentity),
            requiresRePair = false,
        )
        val captured = 70L
        val cache = cacheFleetIdentityVerification(captured, captured, verification)!!
        val adoptedByFirst = legacy.copy(
            baseUrl = "${first.publicUrl}/workspaces/ws",
            hostId = first.hostId,
            workspaceId = "ws",
        )
        val afterFirst = routeOwnershipGenerationAfter(
            current = captured,
            previousAccount = account,
            previousHosts = listOf(first, second),
            previousConnections = listOf(legacy),
            updatedAccount = account,
            updatedHosts = listOf(first, second),
            updatedConnections = listOf(adoptedByFirst),
        )

        assertEquals(listOf(staleIdentity), cache.verificationForGeneration(captured)?.identities)
        assertNull(cache.verificationForGeneration(afterFirst))
    }
    @Test fun a_multi_workspace_legacy_root_authorizes_its_known_direct_host() {
        val account = RelayAccount("new.example", "new-token")
        val directHost = HostConnection(
            hostId = "retained-host",
            directUrl = "http://direct.example",
        )
        val legacyRoot = WorkspaceConnection(
            id = "legacy-root",
            baseUrl = directHost.directUrl!!,
            token = "direct-token",
            hostId = directHost.hostId,
            workspaceId = null,
        )

        val selected = fleetWorkspaceRefreshTargets(
            hosts = listOf(directHost),
            connections = listOf(legacyRoot),
            account = account,
            identities = emptyList(),
            routeOwnershipGeneration = 42L,
        ).single()

        assertEquals("direct-token", selected.directToken)
        assertEquals(listOf(legacyRoot), selected.expectedSourceGeneration.sources)
    }


    @Test fun verified_url_and_null_host_sources_authorize_their_known_direct_host() {
        val account = RelayAccount("new.example", "new-token")
        val directHost = HostConnection(
            hostId = "retained-host",
            directUrl = "http://direct.example",
        )
        val urlSource = WorkspaceConnection(
            id = "url-source",
            baseUrl = "${directHost.directUrl}/workspaces/ws-url",
            token = "direct-token",
            hostId = "HTTP://DIRECT.EXAMPLE",
            workspaceId = "ws-url",
        )
        val nullSource = WorkspaceConnection(
            id = "null-source",
            baseUrl = "${directHost.directUrl}/workspaces/ws-null",
            token = "direct-token",
            hostId = null,
            workspaceId = "ws-null",
        )

        val selected = fleetWorkspaceRefreshTargets(
            hosts = listOf(directHost),
            connections = listOf(urlSource, nullSource),
            account = account,
            identities = listOf(
                VerifiedIdentity(urlSource.id, directHost.hostId, "ws-url"),
                VerifiedIdentity(nullSource.id, directHost.hostId, "ws-null"),
            ),
            routeOwnershipGeneration = 43L,
        ).single()

        assertEquals("direct-token", selected.directToken)
        assertEquals(
            listOf(nullSource, urlSource),
            selected.expectedSourceGeneration.sources,
        )
    }

    @Test fun a_relay_owned_host_without_refresh_is_not_treated_as_retained_direct() {
        val account = RelayAccount("relay.example", "fleet-token")
        val relayHost = HostConnection(
            hostId = "relay-host",
            directUrl = "http://direct.example",
            relayDomain = account.relayDomain,
            refresh = null,
        )
        val preservedDirect = WorkspaceConnection(
            id = "workspace",
            baseUrl = "${relayHost.directUrl}/workspaces/ws",
            directToken = "preserved-direct",
            hostId = relayHost.hostId,
            workspaceId = "ws",
        )

        val target = fleetWorkspaceRefreshTargets(
            hosts = listOf(relayHost),
            connections = listOf(preservedDirect),
            account = account,
            identities = emptyList(),
            routeOwnershipGeneration = 44L,
        ).single()

        assertNull(target.directToken)
        assertEquals(listOf(preservedDirect), target.expectedSourceGeneration.sources)
    }

    @Test fun fleet_failures_distinguish_auth_invalid_data_and_outages() {
        val rejected = classifyFleetRefreshFailure(AuthException("unauthorized"))
        val malformed = classifyFleetRefreshFailure(InvalidRelayDirectoryException("bad payload"))
        val unreachable = classifyFleetRefreshFailure(IOException("offline"))

        assertTrue(rejected.requiresRePair)
        assertEquals("Re-pair needed — scan the relay account again", rejected.message)
        assertFalse(malformed.requiresRePair)
        assertTrue(malformed.preservesCachedFleet)
        assertEquals("Relay returned malformed host data — showing last known hosts", malformed.message)
        assertFalse(unreachable.requiresRePair)
        assertFalse(unreachable.preservesCachedFleet)
        assertEquals("Couldn't reach the relay — showing last known hosts", unreachable.message)
    }

    @Test fun fleet_refresh_failure_classification_propagates_cancellation() {
        val cancellation = CancellationException("scope cancelled")

        val thrown = runCatching {
            classifyFleetRefreshFailure(cancellation)
        }.exceptionOrNull()

        assertTrue(thrown === cancellation)
    }


    @Test fun only_a_persisted_direct_host_can_adopt_a_discovered_identity() {
        assertFalse(canAdoptDirectHostIdentity("host-new", claimedHost = null))
        assertTrue(
            canAdoptDirectHostIdentity(
                "host-direct",
                HostConnection(hostId = "host-direct", refresh = null),
            ),
        )
        assertFalse(
            canAdoptDirectHostIdentity(
                "host-relay",
                HostConnection(hostId = "host-relay", refresh = "secret"),
            ),
        )
        assertFalse(
            canAdoptDirectHostIdentity(
                "host-relay-without-refresh",
                HostConnection(
                    hostId = "host-relay-without-refresh",
                    relayDomain = "relay.example",
                    refresh = null,
                ),
            ),
        )
        assertFalse(canAdoptDirectHostIdentity(claimedHostId = null, claimedHost = null))
        assertFalse(canAdoptDirectHostIdentity("", claimedHost = null))
        assertFalse(canAdoptDirectHostIdentity("   ", claimedHost = null))
        assertFalse(canAdoptDirectHostIdentity("http://host:47190", claimedHost = null))
        assertFalse(canAdoptDirectHostIdentity("https://host.example", claimedHost = null))
    }

    @Test fun an_unverified_discovery_remains_an_unowned_direct_connection() {
        val claimedHostId = "http://direct.example"
        val adoptedHostId = claimedHostId.takeIf {
            canAdoptDirectHostIdentity(it, claimedHost = null)
        }
        val selected = selectedDiscoveryConnection(
            hostBase = claimedHostId,
            hostToken = "direct-token",
            adoptedHostId = adoptedHostId,
            info = WorkspaceInfo(
                id = "ws-1",
                name = "Workspace",
                state = "online",
            ),
        )

        assertEquals("http://direct.example/workspaces/ws-1", selected.baseUrl)
        assertEquals("direct-token", selected.token)
        assertEquals("direct-token", selected.directToken)
        assertNull(selected.hostId)
        assertNull(selected.workspaceId)
    }
    @Test fun a_verified_persisted_host_keeps_its_fleet_identity() {
        val selected = selectedDiscoveryConnection(
            hostBase = "http://direct.example",
            hostToken = "direct-token",
            adoptedHostId = "host-direct",
            info = WorkspaceInfo(
                id = "ws-1",
                name = "Workspace",
                state = "online",
            ),
        )

        assertEquals("host-direct", selected.hostId)
        assertEquals("ws-1", selected.workspaceId)
    }
    @Test fun unresolved_legacy_rows_remain_eligible_after_the_host_is_known() {
        val legacy = WorkspaceConnection(
            id = "manual",
            baseUrl = "http://lan/workspaces/ws-1",
            hostId = "http://lan",
            workspaceId = "ws-1",
        )
        val adopted = legacy.copy(hostId = "host-a")

        assertEquals(listOf(legacy), unresolvedLegacyConnections(listOf(legacy, adopted)))
    }

    @Test fun uppercase_url_host_handles_remain_eligible_for_legacy_verification() {
        val legacy = WorkspaceConnection(
            id = "manual",
            baseUrl = "https://host.example/workspaces/ws-1",
            hostId = "HTTPS://host.example",
            workspaceId = "ws-1",
        )

        assertEquals(listOf(legacy), unresolvedLegacyConnections(listOf(legacy)))
    }

    @Test fun direct_discovery_verifies_requested_and_reached_legacy_rows() {
        val requestedRoot = WorkspaceConnection("root", "http://host:47190")
        val requestedWorkspace =
            WorkspaceConnection("workspace", "http://host:47190/workspaces/ws-1")
        val reachedWorkspace =
            WorkspaceConnection("public", "https://host.example/workspaces/ws-1")
        val unrelated = WorkspaceConnection("other", "http://other:47190")

        val matches = legacyConnectionsForDiscovery(
            connections = listOf(
                requestedRoot,
                requestedWorkspace,
                reachedWorkspace,
                unrelated,
            ),
            hostBases = listOf("http://host:47190/", "https://host.example"),
            workspaceId = "ws-1",
        )

        assertEquals(
            listOf(requestedRoot, requestedWorkspace, reachedWorkspace),
            matches,
        )
    }

    @Test fun public_fallback_is_not_persisted_as_a_direct_url() {
        assertNull(
            directUrlForDiscovery(
                requestedBase = "http://host:47190",
                reachedBase = "https://host.example",
            ),
        )
        assertEquals(
            "http://host:47190",
            directUrlForDiscovery(
                requestedBase = "http://host:47190/",
                reachedBase = "http://host:47190",
            ),
        )
    }

    @Test fun fleet_status_is_hidden_as_soon_as_its_relay_account_is_replaced() {
        val old = RelayAccount("relay.example", "old-token")
        val replacement = RelayAccount("relay.example", "new-token")

        assertEquals("Fleet: 2 host(s)", visibleSettingsResult("Fleet: 2 host(s)", old, old))
        assertNull(visibleSettingsResult("Fleet: 2 host(s)", old, replacement))
    }
    @Test fun refresh_credential_sources_require_matching_route_evidence() {
        val account = RelayAccount("relay.example", "fleet-token")
        val host = HostConnection(
            hostId = "host-1",
            publicUrl = "https://host.example/root",
            relayDomain = account.relayDomain,
        )
        val conflictingRows = listOf(
            WorkspaceConnection(
                "wrong-host",
                "https://host.example/root/workspaces/ws-1",
                hostId = "other-host",
                workspaceId = "ws-1",
                directToken = "secret",
            ),
            WorkspaceConnection(
                "wrong-workspace",
                "https://host.example/root/workspaces/ws-1",
                hostId = host.hostId,
                workspaceId = "other-workspace",
                directToken = "secret",
            ),
            WorkspaceConnection(
                "unknown",
                "https://unknown.example/root/workspaces/ws-1",
                directToken = "secret",
            ),
        )

        val target = fleetWorkspaceRefreshTarget(
            host = host,
            hosts = listOf(host),
            connections = conflictingRows,
            account = account,
            identities = emptyList(),
            routeOwnershipGeneration = 4L,
        )!!

        assertTrue(target.expectedSourceGeneration.sources.isEmpty())
    }
}
