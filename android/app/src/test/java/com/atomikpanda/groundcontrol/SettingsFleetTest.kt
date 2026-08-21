package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.LegacyIdentityVerification
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.mshipDefaults
import com.atomikpanda.groundcontrol.data.hostsFrom
import com.atomikpanda.groundcontrol.data.replaceRelayHosts
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.settings.canAdoptDirectHostIdentity
import com.atomikpanda.groundcontrol.ui.settings.classifyFleetRefreshFailure
import com.atomikpanda.groundcontrol.ui.settings.classifyMalformedFleetDirectoryFailure
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
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.ktor.serialization.JsonConvertException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFleetTest {
    private fun directoryApi(payload: String, contentType: String = "application/json") =
        SpecApi(HttpClient(MockEngine {
            respond(
                payload,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, contentType),
            )
        }) { mshipDefaults() })

    private suspend fun assertInvalidDirectoryKeepsCachedFleet(payload: String) {
        val relayDomain = "relay.example.com"
        val cached = HostConnection(
            hostId = "cached",
            publicUrl = "https://cached.relay.example.com",
            relayDomain = relayDomain,
            state = "online",
        )
        var persisted = listOf(cached)

        val error = runCatching {
            val entries = directoryApi(payload).listHosts(relayDomain, "fleet-token")
            persisted = replaceRelayHosts(persisted, relayDomain, hostsFrom(entries, relayDomain))
        }.exceptionOrNull()

        assertTrue(error != null)
        assertFalse(classifyMalformedFleetDirectoryFailure(error!!).markRelayUnreachable)
        assertEquals(listOf(cached), persisted)
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

    @Test fun malformed_fleet_directory_preserves_cached_observations() {
        val malformed = classifyMalformedFleetDirectoryFailure(
            IllegalStateException("Relay directory contained an unusable host identity"),
        )
        val rejected = classifyFleetRefreshFailure(AuthException("unauthorized"))
        val unreachable = classifyFleetRefreshFailure(IOException("offline"))

        assertFalse(malformed.requiresRePair)
        assertFalse(malformed.markRelayUnreachable)
        assertEquals(
            "Relay returned malformed host data — showing last known hosts",
            malformed.message,
        )
        assertTrue(rejected.requiresRePair)
        assertFalse(rejected.markRelayUnreachable)
        assertEquals("Re-pair needed — scan the relay account again", rejected.message)
        assertFalse(unreachable.requiresRePair)
        assertTrue(unreachable.markRelayUnreachable)
        assertEquals("Couldn't reach the relay — showing last known hosts", unreachable.message)
    }

    @Test fun malformed_http_directory_response_preserves_cached_observations() = runTest {
        val api = directoryApi("""{"hosts":[{"last_seen":"not-a-number"}]}""")

        val error = runCatching {
            api.listHosts("relay.example.com", "fleet-token")
        }.exceptionOrNull()!!
        assertTrue(error is JsonConvertException)
        val failure = classifyFleetRefreshFailure(error)

        assertFalse(failure.requiresRePair)
        assertFalse(failure.markRelayUnreachable)
        assertEquals(
            "Relay returned malformed host data — showing last known hosts",
            failure.message,
        )
    }

    @Test fun missing_hosts_in_http_directory_response_preserve_cached_observations() = runTest {
        val error = runCatching {
            directoryApi("{}").listHosts("relay.example.com", "fleet-token")
        }.exceptionOrNull()!!

        assertTrue(error is JsonConvertException)
        assertFalse(classifyFleetRefreshFailure(error).markRelayUnreachable)
    }

    @Test fun null_hosts_in_http_directory_response_preserve_cached_observations() = runTest {
        val error = runCatching {
            directoryApi("""{"hosts":null}""").listHosts("relay.example.com", "fleet-token")
        }.exceptionOrNull()!!

        assertTrue(error is JsonConvertException)
        assertFalse(classifyFleetRefreshFailure(error).markRelayUnreachable)
    }

    @Test fun empty_hosts_in_http_directory_response_are_allowed() = runTest {
        val hosts = directoryApi("""{"hosts":[]}""")
            .listHosts("relay.example.com", "fleet-token")

        assertTrue(hosts.isEmpty())
    }

    @Test fun incompatible_content_type_for_http_directory_response_preserves_cached_observations() = runTest {
        val error = runCatching {
            directoryApi("""{"hosts":[]}""", contentType = "text/plain")
                .listHosts("relay.example.com", "fleet-token")
        }.exceptionOrNull()!!

        assertTrue(error is NoTransformationFoundException)
        assertFalse(classifyFleetRefreshFailure(error).markRelayUnreachable)
    }

    @Test fun duplicate_host_identity_in_http_directory_preserves_cached_fleet() = runTest {
        assertInvalidDirectoryKeepsCachedFleet(
            """{"hosts":[
                {"host_id":"host-a","public_url":"https://a.relay.example.com"},
                {"host_id":"host-a","public_url":"https://b.relay.example.com"}
            ]}""",
        )
    }

    @Test fun duplicate_pending_request_in_http_directory_preserves_cached_fleet() = runTest {
        assertInvalidDirectoryKeepsCachedFleet(
            """{"hosts":[
                {"state":"pending-approval","request_id":"request-a"},
                {"state":"pending-approval","request_id":"request-a"}
            ]}""",
        )
    }

    @Test fun route_less_approved_host_in_http_directory_preserves_cached_fleet() = runTest {
        assertInvalidDirectoryKeepsCachedFleet(
            """{"hosts":[{"host_id":"host-a","state":"online"}]}""",
        )
    }

    @Test fun valid_directory_entries_preserve_pending_rows_and_status_count() = runTest {
        val relayDomain = "relay.example.com"
        val entries = directoryApi(
            """{"hosts":[
                {"host_id":"host-a","public_url":"https://a.relay.example.com"},
                {"state":"pending-approval","request_id":"request-a"}
            ]}""",
        ).listHosts(relayDomain, "fleet-token")

        val stored = replaceRelayHosts(emptyList(), relayDomain, hostsFrom(entries, relayDomain))

        assertEquals(entries.size, stored.size)
        assertEquals(listOf("host-a", "pending:request-a"), stored.map { it.hostId })
    }

    @Test fun relay_replacement_canonicalizes_public_direct_and_legacy_routes() = runTest {
        val relayDomain = "relay.example.com"
        val existing = HostConnection(
            hostId = "host-a",
            publicUrl = " https://old.relay.example.com/ ",
            directUrl = " https://lan.example:47190/ ",
            legacyPublicUrls = listOf(" https://legacy.relay.example.com/ "),
            relayDomain = relayDomain,
        )
        val entries = directoryApi(
            """{"hosts":[{"host_id":"host-a","public_url":" https://new.relay.example.com/ "}]}""",
        ).listHosts(relayDomain, "fleet-token")

        val stored = replaceRelayHosts(listOf(existing), relayDomain, hostsFrom(entries, relayDomain)).single()

        assertEquals("https://new.relay.example.com", stored.publicUrl)
        assertEquals("https://lan.example:47190", stored.directUrl)
        assertEquals(
            listOf(
                "https://legacy.relay.example.com",
                "https://old.relay.example.com",
            ),
            stored.legacyPublicUrls,
        )
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
