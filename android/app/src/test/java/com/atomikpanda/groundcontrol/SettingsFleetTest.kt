package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.settings.canAdoptDirectHostIdentity
import com.atomikpanda.groundcontrol.ui.settings.classifyFleetRefreshFailure
import com.atomikpanda.groundcontrol.ui.settings.directUrlForDiscovery
import com.atomikpanda.groundcontrol.ui.settings.observeRelayAccountChanges
import com.atomikpanda.groundcontrol.data.unresolvedLegacyConnections
import com.atomikpanda.groundcontrol.ui.settings.fleetWorkspaceRefreshTargets
import com.atomikpanda.groundcontrol.ui.settings.legacyConnectionsForDiscovery
import com.atomikpanda.groundcontrol.ui.settings.visibleSettingsResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFleetTest {
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
        )

        assertEquals(listOf(relayHost, retainedDirect), selected.map { it.host })
        assertEquals(listOf("relay-refresh", null), selected.map { it.expectedRelayRefresh })
        assertEquals(listOf(null, "direct-token"), selected.map { it.directToken })
        assertEquals(
            listOf(emptyList(), listOf(stableDirectConnection)),
            selected.map { it.expectedSourceGeneration.sources },
        )
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
        ).single()

        assertNull(target.directToken)
        assertEquals(listOf(preservedDirect), target.expectedSourceGeneration.sources)
    }

    @Test fun fleet_auth_failure_requires_repair_but_transport_failure_is_an_outage() {
        val rejected = classifyFleetRefreshFailure(AuthException("unauthorized"))
        val unreachable = classifyFleetRefreshFailure(IOException("offline"))

        assertTrue(rejected.requiresRePair)
        assertEquals("Re-pair needed — scan the relay account again", rejected.message)
        assertFalse(unreachable.requiresRePair)
        assertEquals("Couldn't reach the relay — showing last known hosts", unreachable.message)
    }

    @Test fun fleet_refresh_failure_classification_propagates_cancellation() {
        val cancellation = CancellationException("scope cancelled")

        val thrown = runCatching {
            classifyFleetRefreshFailure(cancellation)
        }.exceptionOrNull()

        assertTrue(thrown === cancellation)
    }


    @Test fun a_new_direct_only_host_can_adopt_its_verified_identity() {
        assertTrue(canAdoptDirectHostIdentity("host-new", claimedHost = null))
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
}
