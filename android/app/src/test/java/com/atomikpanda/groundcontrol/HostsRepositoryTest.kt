package com.atomikpanda.groundcontrol

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.WorkspaceRefreshGeneration
import com.atomikpanda.groundcontrol.data.HostsCodec
import com.atomikpanda.groundcontrol.data.hostBase
import com.atomikpanda.groundcontrol.data.hostFrom
import com.atomikpanda.groundcontrol.data.hostsFrom
import com.atomikpanda.groundcontrol.data.ladderFor
import com.atomikpanda.groundcontrol.data.markRelayUnreachable
import com.atomikpanda.groundcontrol.data.replaceRelayHosts
import com.atomikpanda.groundcontrol.data.replaceRelayDirectoryFleet
import com.atomikpanda.groundcontrol.data.replaceRelayAccountFleet
import com.atomikpanda.groundcontrol.data.recordDirectHostDiscovery
import com.atomikpanda.groundcontrol.data.routeOwnershipGenerationAfter
import com.atomikpanda.groundcontrol.data.upsertHost
import com.atomikpanda.groundcontrol.data.upsertConnection
import com.atomikpanda.groundcontrol.data.dto.HostInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The host model persisted under the "hosts" DataStore key (#471). */
class HostsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val hostsKey = stringPreferencesKey("hosts")
    private val connectionsKey = stringPreferencesKey("connections")
    private val generationKey = longPreferencesKey("route_ownership_generation")
    private val relayDomainKey = stringPreferencesKey("relay_domain")
    private val relayFleetTokenKey = stringPreferencesKey("relay_fleet_token")

    private data class RawRouteOwnershipState(
        val hosts: String?,
        val connections: String?,
        val relayDomain: String?,
        val relayFleetToken: String?,
        val generation: Long?,
    )

    private suspend fun rawRouteOwnershipState(dataStore: DataStore<Preferences>) =
        dataStore.data.first().let {
            RawRouteOwnershipState(
                it[hostsKey],
                it[connectionsKey],
                it[relayDomainKey],
                it[relayFleetTokenKey],
                it[generationKey],
            )
        }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (caught: Throwable) {
            caught
        }
        assertTrue(error is IllegalArgumentException)
    }

    private fun newDataStore(name: String, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile(name).also { check(it.delete()) }
        }

    private val host = HostConnection(
        hostId = "h-1",
        label = "vm-a",
        subdomain = "h-1abc",
        publicUrl = "https://h-1abc.relay.example.com",
        state = "online",
        refresh = "refresh-credential",
        relayDomain = "relay.example.com",
    )

    @Test fun codec_round_trips() {
        val out = HostsCodec.decode(HostsCodec.encode(listOf(host)))
        assertEquals(listOf(host), out)
    }

    @Test fun the_refresh_credential_is_persisted() {
        // AC9: a cached public host stays usable while directory discovery is
        // down only if the refresh credential survives a process death — it is
        // stored, not held in memory beside the directory response.
        val out = HostsCodec.decode(HostsCodec.encode(listOf(host)))
        assertEquals("refresh-credential", out[0].refresh)
    }

    @Test fun upsert_keys_on_host_id_and_carries_operator_fields_forward() {
        val stored = upsertHost(
            emptyList(),
            host.copy(labelOverride = "kitchen box", directUrl = "http://192.168.1.9:47190"),
        )
        // A later directory read carries neither operator field, and a rotated
        // subdomain/public URL must not read as a different host.
        val fresh = host.copy(subdomain = "h-1zzz", publicUrl = "https://h-1zzz.relay.example.com")
        val out = upsertHost(stored, fresh)
        assertEquals(1, out.size)
        assertEquals("h-1", out[0].hostId)
        assertEquals("h-1zzz", out[0].subdomain)
        assertEquals("kitchen box", out[0].labelOverride)
        assertEquals("http://192.168.1.9:47190", out[0].directUrl)
        assertEquals(listOf(host.publicUrl), out[0].legacyPublicUrls)
    }

    @Test fun upsert_keeps_a_prior_refresh_when_the_directory_omits_one() {
        // GET /hosts is the only route that publishes `refresh`; an entry read from
        // anywhere else (or a pending-approval row) must not erase the one we hold.
        val stored = upsertHost(emptyList(), host)
        val out = upsertHost(stored, host.copy(refresh = null))
        assertEquals("refresh-credential", out[0].refresh)
    }

    @Test fun two_hosts_stay_independent() {
        // AC5: neither host's failure hides or degrades the other.
        val out = upsertHost(upsertHost(emptyList(), host), host.copy(hostId = "h-2", state = "offline"))
        assertEquals(listOf("h-1", "h-2"), out.map { it.hostId })
    }

    @Test fun a_direct_only_host_prefers_its_reachable_direct_url() {
        assertEquals("https://h-1abc.relay.example.com", host.hostBase())
        assertEquals(
            "http://192.168.1.9:47190",
            host.copy(
                refresh = null,
                relayDomain = null,
                directUrl = "http://192.168.1.9:47190/",
            ).hostBase(),
        )
    }

    @Test fun an_unverified_direct_url_cannot_receive_a_stored_refresh_credential() {
        val claimed = host.copy(directUrl = "http://attacker.lan:47190")

        assertEquals(host.publicUrl, claimed.hostBase())
    }
    @Test fun a_failed_directory_read_marks_only_that_relays_hosts_unknown() {
        val other = host.copy(hostId = "h-2", relayDomain = "other.example.com", state = "online")
        val out = markRelayUnreachable(listOf(host, other), "relay.example.com")
        assertNull(out.first { it.hostId == "h-1" }.state)
        assertEquals("refresh-credential", out.first { it.hostId == "h-1" }.refresh)
        assertEquals("online", out.first { it.hostId == "h-2" }.state)
    }

    @Test fun a_successful_directory_read_replaces_stale_pending_rows() {
        val pending = HostConnection(
            hostId = "pending:req-1",
            label = "vm-a",
            state = "pending-approval",
            relayDomain = "relay.example.com",
            requestId = "req-1",
        )
        val out = replaceRelayHosts(listOf(pending), "relay.example.com", listOf(host))
        assertEquals(listOf("h-1"), out.map { it.hostId })
        assertEquals("refresh-credential", out.single().refresh)
    }

    @Test fun directory_entries_require_a_non_blank_host_or_request_identity() {
        assertNull(hostFrom(HostInfo(hostId = ""), "relay.example.com"))
        assertNull(hostFrom(HostInfo(hostId = "  ", requestId = " "), "relay.example.com"))
        assertEquals(
            "pending:req-1",
            hostFrom(
                HostInfo(hostId = "", requestId = "req-1"),
                "relay.example.com",
            )?.hostId,
        )
    }

    @Test fun a_non_empty_directory_without_any_usable_identity_is_rejected() {
        val result = runCatching {
            hostsFrom(
                listOf(
                    HostInfo(hostId = ""),
                    HostInfo(hostId = "  ", requestId = " "),
                ),
                "relay.example.com",
            )
        }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test fun a_mixed_directory_rejects_the_whole_authoritative_decode() {
        val result = runCatching {
            hostsFrom(
                listOf(
                    HostInfo(hostId = "valid-host"),
                    HostInfo(hostId = "", requestId = " "),
                ),
                "relay.example.com",
            )
        }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test fun a_genuinely_empty_directory_remains_authoritative() {
        val workspace = WorkspaceConnection(
            id = "removed",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )

        val replaced = replaceRelayDirectoryFleet(
            relayDomain = "relay.example.com",
            existingHosts = listOf(host),
            replacementHosts = hostsFrom(emptyList(), "relay.example.com"),
            connections = listOf(workspace),
        )

        assertEquals(emptyList<HostConnection>(), replaced.hosts)
        assertEquals(emptyList<WorkspaceConnection>(), replaced.connections)
    }

    @Test fun direct_discovery_generation_rejects_an_account_A_B_A_change() {
        val accountA = RelayAccount("relay.example.com", "token-a")
        val accountB = RelayAccount("other.example.com", "token-b")
        val captured = 7L
        val afterB = routeOwnershipGenerationAfter(
            current = captured,
            previousAccount = accountA,
            previousHosts = listOf(host),
            previousConnections = emptyList(),
            updatedAccount = accountB,
            updatedHosts = listOf(host),
            updatedConnections = emptyList(),
        )
        val afterA = routeOwnershipGenerationAfter(
            current = afterB,
            previousAccount = accountB,
            previousHosts = listOf(host),
            previousConnections = emptyList(),
            updatedAccount = accountA,
            updatedHosts = listOf(host),
            updatedConnections = emptyList(),
        )

        assertEquals(captured + 2, afterA)
        assertFalse(captured == afterA)
    }

    @Test fun fleet_generation_rejects_a_host_route_A_B_A_change() {
        val routeB = host.copy(publicUrl = "https://moved.relay.example.com")
        val captured = 11L
        val afterB = routeOwnershipGenerationAfter(
            current = captured,
            previousAccount = null,
            previousHosts = listOf(host),
            previousConnections = emptyList(),
            updatedAccount = null,
            updatedHosts = listOf(routeB),
            updatedConnections = emptyList(),
        )
        val afterA = routeOwnershipGenerationAfter(
            current = afterB,
            previousAccount = null,
            previousHosts = listOf(routeB),
            previousConnections = emptyList(),
            updatedAccount = null,
            updatedHosts = listOf(host),
            updatedConnections = emptyList(),
        )

        assertEquals(captured + 2, afterA)
        assertFalse(captured == afterA)
    }

    @Test fun route_generation_tracks_host_credential_and_ownership_changes() {
        listOf(
            host.copy(refresh = "rotated-refresh"),
            host.copy(relayDomain = "other.example.com"),
            host.copy(directUrl = "http://direct.example"),
            host.copy(legacyPublicUrls = listOf("https://old.example.com")),
        ).forEach { changed ->
            assertEquals(
                17L,
                routeOwnershipGenerationAfter(
                    current = 16L,
                    previousAccount = null,
                    previousHosts = listOf(host),
                    previousConnections = emptyList(),
                    updatedAccount = null,
                    updatedHosts = listOf(changed),
                    updatedConnections = emptyList(),
                ),
            )
        }
    }

    @Test fun route_generation_ignores_contact_status_runner_and_connection_display_changes() {
        val connection = WorkspaceConnection(
            id = "workspace",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            token = "token",
            workspaceName = "Before",
            colorOverride = "#FF000000",
            glyphOverride = "B",
            hostId = host.hostId,
            state = "online",
            workspaceId = "ws",
        )
        val observedHost = host.copy(
            state = "offline",
            lastSeen = 42.0,
            runnerState = "busy",
            lastContactAtMillis = 123L,
            label = "Observed",
            labelOverride = "Mine",
        )
        val displayedConnection = connection.copy(
            workspaceName = "After",
            colorOverride = "#FFFFFFFF",
            glyphOverride = "A",
            state = "offline",
        )

        assertEquals(
            19L,
            routeOwnershipGenerationAfter(
                current = 19L,
                previousAccount = null,
                previousHosts = listOf(host),
                previousConnections = listOf(connection),
                updatedAccount = null,
                updatedHosts = listOf(observedHost),
                updatedConnections = listOf(displayedConnection),
            ),
        )
    }

    @Test fun route_generation_tracks_every_connection_routing_ownership_and_credential_field() {
        val connection = WorkspaceConnection(
            id = "workspace",
            baseUrl = "https://relay.example.com/workspaces/ws",
            token = "token",
            hostId = "host",
            workspaceId = "ws",
            legacyBaseUrls = listOf("https://old.example.com/workspaces/ws"),
            legacyConnectionIds = listOf("old-id"),
            directToken = "direct-token",
        )
        val changes = listOf(
            connection.copy(id = "new-id"),
            connection.copy(baseUrl = "https://moved.example.com/workspaces/ws"),
            connection.copy(token = "new-token"),
            connection.copy(hostId = "new-host"),
            connection.copy(workspaceId = "new-workspace"),
            connection.copy(legacyBaseUrls = listOf("https://older.example.com/workspaces/ws")),
            connection.copy(legacyConnectionIds = listOf("older-id")),
            connection.copy(directToken = "new-direct-token"),
        )

        changes.forEach { changed ->
            assertEquals(
                24L,
                routeOwnershipGenerationAfter(
                    current = 23L,
                    previousAccount = null,
                    previousHosts = emptyList(),
                    previousConnections = listOf(connection),
                    updatedAccount = null,
                    updatedHosts = emptyList(),
                    updatedConnections = listOf(changed),
                ),
            )
        }
    }

    @Test fun direct_discovery_rejects_duplicate_persisted_identity_sources_atomically() = runTest {
        val generation = 41L
        val directHost = host.copy(
            publicUrl = "",
            refresh = null,
            relayDomain = null,
            directUrl = "http://direct.example",
        )
        val firstSource = WorkspaceConnection(
            id = "duplicate-source",
            baseUrl = "${directHost.directUrl}/workspaces/ws",
            token = "first-token",
            directToken = "first-direct-token",
        )
        val secondSource = firstSource.copy(
            baseUrl = "http://legacy-direct.example/workspaces/ws",
            token = "second-token",
            directToken = "second-direct-token",
        )
        val existingConnections = listOf(firstSource, secondSource)
        val dataStore = newDataStore("direct-duplicate.preferences_pb", backgroundScope)
        dataStore.edit {
            it[hostsKey] = HostsCodec.encode(listOf(directHost))
            it[connectionsKey] = ConnectionsCodec.encode(existingConnections)
            it[generationKey] = generation
        }

        val applied = HostsRepository(dataStore).applyDiscoveredWorkspace(
            expectedGeneration = generation,
            directHostId = directHost.hostId,
            discovered = WorkspaceConnection(
                id = "discovered",
                baseUrl = "http://moved-direct.example/workspaces/ws",
                token = "discovered-token",
                hostId = directHost.hostId,
                workspaceId = "ws",
            ),
            identities = listOf(VerifiedIdentity(firstSource.id, directHost.hostId, "ws")),
            activatePriorDirectToken = true,
            directUrl = "http://moved-direct.example",
            runnerState = "busy",
            contactedAtMillis = 99L,
        )

        val persisted = dataStore.data.first()
        val persistedConnections = ConnectionsCodec.decode(persisted[connectionsKey] ?: "")
        assertFalse(applied)
        assertEquals(generation, persisted[generationKey])
        assertEquals(listOf(directHost), HostsCodec.decode(persisted[hostsKey] ?: ""))
        assertEquals(existingConnections, persistedConnections)
        assertEquals(listOf("first-token", "second-token"), persistedConnections.map { it.token })
        assertEquals(
            listOf("first-direct-token", "second-direct-token"),
            persistedConnections.map { it.directToken },
        )
    }

    @Test fun fleet_apply_rejects_duplicate_verified_identity_sources_atomically() = runTest {
        val generation = 42L
        val source = WorkspaceConnection(
            id = "verified-source",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            token = "standing-token",
            directToken = "standing-direct-token",
        )
        val identity = VerifiedIdentity(source.id, host.hostId, "ws")
        val dataStore = newDataStore("fleet-duplicate.preferences_pb", backgroundScope)
        dataStore.edit {
            it[hostsKey] = HostsCodec.encode(listOf(host))
            it[connectionsKey] = ConnectionsCodec.encode(listOf(source))
            it[generationKey] = generation
        }

        val applied = HostsRepository(dataStore).applyHostWorkspaceRefresh(
            hostId = host.hostId,
            hostBase = host.publicUrl,
            expectedDirectOnly = false,
            expectedSourceGeneration = WorkspaceRefreshGeneration(
                routeOwnershipGeneration = generation,
                sources = listOf(source),
            ),
            contactedAtMillis = 100L,
            discovered = listOf(
                WorkspaceConnection(
                    id = "discovered",
                    baseUrl = "${host.publicUrl}/workspaces/ws",
                    hostId = host.hostId,
                    workspaceId = "ws",
                ),
            ),
            identities = listOf(identity, identity),
        )

        val persisted = dataStore.data.first()
        val persistedConnections = ConnectionsCodec.decode(persisted[connectionsKey] ?: "")
        assertFalse(applied)
        assertEquals(generation, persisted[generationKey])
        assertEquals(listOf(host), HostsCodec.decode(persisted[hostsKey] ?: ""))
        assertEquals(listOf(source), persistedConnections)
        assertEquals("standing-token", persistedConnections.single().token)
        assertEquals("standing-direct-token", persistedConnections.single().directToken)
    }

    @Test fun atomic_discovery_still_applies_one_unique_verified_identity_source() = runTest {
        val generation = 43L
        val directHost = host.copy(
            publicUrl = "",
            refresh = null,
            relayDomain = null,
            directUrl = "http://direct.example",
        )
        val source = WorkspaceConnection(
            id = "unique-source",
            baseUrl = "${directHost.directUrl}/workspaces/ws",
            token = "standing-token",
            directToken = "standing-direct-token",
        )
        val dataStore = newDataStore("direct-unique.preferences_pb", backgroundScope)
        dataStore.edit {
            it[hostsKey] = HostsCodec.encode(listOf(directHost))
            it[connectionsKey] = ConnectionsCodec.encode(listOf(source))
            it[generationKey] = generation
        }

        val applied = HostsRepository(dataStore).applyDiscoveredWorkspace(
            expectedGeneration = generation,
            directHostId = directHost.hostId,
            discovered = WorkspaceConnection(
                id = "discovered",
                baseUrl = "${directHost.directUrl}/workspaces/ws",
                hostId = directHost.hostId,
                workspaceId = "ws",
            ),
            identities = listOf(VerifiedIdentity(source.id, directHost.hostId, "ws")),
            activatePriorDirectToken = true,
            directUrl = directHost.directUrl,
            runnerState = "idle",
            contactedAtMillis = 101L,
        )

        val persisted = dataStore.data.first()
        val adopted = ConnectionsCodec.decode(persisted[connectionsKey] ?: "").single()
        assertTrue(applied)
        assertEquals(generation + 1, persisted[generationKey])
        assertEquals(source.id, adopted.id)
        assertEquals(directHost.hostId, adopted.hostId)
        assertEquals("ws", adopted.workspaceId)
        assertEquals("standing-direct-token", adopted.token)
        assertEquals("standing-direct-token", adopted.directToken)
        assertEquals(101L, HostsCodec.decode(persisted[hostsKey] ?: "").single().lastContactAtMillis)
    }

    @Test fun replacing_a_relay_account_clears_only_the_previous_fleet() {
        val otherHost = host.copy(
            hostId = "h-2",
            publicUrl = "https://other.relay.example.com",
            relayDomain = "other.example.com",
        )
        val oldWorkspace = WorkspaceConnection(
            id = "old",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val otherWorkspace = oldWorkspace.copy(
            id = "other",
            baseUrl = "${otherHost.publicUrl}/workspaces/ws",
            hostId = otherHost.hostId,
        )
        val manualWorkspace = WorkspaceConnection(id = "manual", baseUrl = "http://lan")

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host, otherHost),
            connections = listOf(oldWorkspace, otherWorkspace, manualWorkspace),
        )

        assertEquals(listOf("h-2"), replaced.hosts.map { it.hostId })
        assertEquals(listOf("other", "manual"), replaced.connections.map { it.id })
    }

    @Test fun replacing_a_relay_account_restores_adopted_direct_workspaces() {
        val directBase = "http://lan:47190"
        val direct = WorkspaceConnection(
            id = "direct",
            baseUrl = "$directBase/workspaces/ws",
            token = "direct-token",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val relay = direct.copy(
            baseUrl = "${host.publicUrl}/workspaces/ws",
            token = null,
        )
        val adopted = upsertConnection(listOf(direct), relay).single()

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host.copy(directUrl = directBase)),
            connections = listOf(adopted),
        )

        assertEquals(1, replaced.hosts.size)
        assertEquals(directBase, replaced.hosts.single().directUrl)
        assertNull(replaced.hosts.single().relayDomain)
        assertNull(replaced.hosts.single().refresh)
        assertEquals("", replaced.hosts.single().publicUrl)
        assertEquals(1, replaced.connections.size)
        assertEquals("$directBase/workspaces/ws", replaced.connections.single().baseUrl)
        assertEquals("direct-token", replaced.connections.single().token)
    }

    @Test fun replacing_a_relay_account_recovers_a_legacy_direct_credential_from_its_host() {
        val directBase = "http://lan:47190"
        val credentialOwner = WorkspaceConnection(
            id = "credential-owner",
            baseUrl = "$directBase/workspaces/other",
            token = "standing-token",
            hostId = host.hostId,
            workspaceId = "other",
        )
        val preDirectTokenAdoption = WorkspaceConnection(
            id = "adopted",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            token = null,
            hostId = host.hostId,
            workspaceId = "ws",
            directToken = null,
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host.copy(directUrl = directBase)),
            connections = listOf(credentialOwner, preDirectTokenAdoption),
        )

        val restored = replaced.connections.single { it.id == "adopted" }
        assertEquals("$directBase/workspaces/ws", restored.baseUrl)
        assertEquals("standing-token", restored.token)
        assertEquals("standing-token", restored.directToken)
    }

    @Test fun replacing_a_relay_account_preserves_same_host_rows_when_credentials_disagree() {
        val directBase = "http://lan:47190"
        val first = WorkspaceConnection(
            id = "first",
            baseUrl = "${host.publicUrl}/workspaces/first",
            hostId = host.hostId,
            workspaceId = "first",
            directToken = "first-token",
        )
        val second = WorkspaceConnection(
            id = "second",
            baseUrl = "${host.publicUrl}/workspaces/second",
            hostId = host.hostId,
            workspaceId = "second",
            directToken = "second-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host.copy(directUrl = directBase)),
            connections = listOf(first, second),
        )

        assertEquals(listOf(first, second), replaced.connections)
    }

    @Test fun replacing_a_relay_account_removes_an_owned_route_without_a_direct_credential() {
        val unrestorable = WorkspaceConnection(
            id = "unrestorable",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host.copy(directUrl = "http://lan:47190")),
            connections = listOf(unrestorable),
        )

        assertEquals(emptyList<WorkspaceConnection>(), replaced.connections)
    }

    @Test fun replacing_a_relay_account_restores_a_legacy_direct_workspace_route() {
        val directBase = "http://lan:47190"
        val legacy = WorkspaceConnection(
            id = "legacy",
            baseUrl = "${host.publicUrl}/workspaces/legacy-ws",
            token = null,
            hostId = host.hostId,
            workspaceId = null,
            directToken = "direct-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host.copy(directUrl = "$directBase/")),
            connections = listOf(legacy),
        )

        val retainedHost = replaced.hosts.single()
        assertEquals(host.hostId, retainedHost.hostId)
        assertEquals(directBase, retainedHost.directUrl)
        assertNull(retainedHost.relayDomain)
        assertEquals("", retainedHost.publicUrl)
        assertNull(retainedHost.refresh)

        val restored = replaced.connections.single()
        assertEquals(host.hostId, restored.hostId)
        assertEquals("legacy-ws", restored.workspaceId)
        assertEquals("$directBase/workspaces/legacy-ws", restored.baseUrl)
        assertEquals("direct-token", restored.token)
        assertEquals("direct-token", restored.directToken)
        assertTrue(restored.legacyBaseUrls.contains(legacy.baseUrl))
    }

    @Test fun replacing_a_relay_account_restores_unambiguous_legacy_host_handles() {
        val directBase = "http://lan:47190"
        val retainedHost = host.copy(directUrl = directBase)
        val urlValued = WorkspaceConnection(
            id = "url-valued",
            baseUrl = "${host.publicUrl}/workspaces/ws-url",
            hostId = host.publicUrl,
            directToken = "url-token",
        )
        val nullHost = WorkspaceConnection(
            id = "null-host",
            baseUrl = "${host.publicUrl}/workspaces/ws-null",
            hostId = null,
            directToken = "url-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(retainedHost),
            connections = listOf(urlValued, nullHost),
        )

        val restoredById = replaced.connections.associateBy { it.id }
        assertEquals(host.hostId, restoredById.getValue("url-valued").hostId)
        assertEquals("ws-url", restoredById.getValue("url-valued").workspaceId)
        assertEquals("$directBase/workspaces/ws-url", restoredById.getValue("url-valued").baseUrl)
        assertEquals("url-token", restoredById.getValue("url-valued").token)
        assertEquals("url-token", restoredById.getValue("url-valued").directToken)
        assertEquals(listOf(urlValued.baseUrl), restoredById.getValue("url-valued").legacyBaseUrls)
        assertEquals(host.hostId, restoredById.getValue("null-host").hostId)
        assertEquals("ws-null", restoredById.getValue("null-host").workspaceId)
        assertEquals("$directBase/workspaces/ws-null", restoredById.getValue("null-host").baseUrl)
        assertEquals("url-token", restoredById.getValue("null-host").token)
        assertEquals("url-token", restoredById.getValue("null-host").directToken)
        assertEquals(listOf(nullHost.baseUrl), restoredById.getValue("null-host").legacyBaseUrls)
    }

    @Test fun replacing_a_relay_account_removes_a_legacy_route_owned_by_a_host_without_direct_access() {
        val stale = WorkspaceConnection(
            id = "stale",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            hostId = host.publicUrl,
            directToken = "standing-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(host.copy(directUrl = null)),
            connections = listOf(stale),
        )

        assertEquals(emptyList<WorkspaceConnection>(), replaced.connections)
    }

    @Test fun replacing_a_relay_account_does_not_guess_legacy_host_ownership() {
        val sharedOldUrl = "https://shared.relay.example.com"
        val first = host.copy(
            hostId = "h-first",
            publicUrl = "https://first.relay.example.com",
            directUrl = "http://first.lan:47190",
            legacyPublicUrls = listOf(sharedOldUrl),
        )
        val second = host.copy(
            hostId = "h-second",
            publicUrl = "https://second.relay.example.com",
            directUrl = "http://second.lan:47190",
            legacyPublicUrls = listOf(sharedOldUrl),
        )
        val ambiguous = WorkspaceConnection(
            id = "ambiguous",
            baseUrl = "$sharedOldUrl/workspaces/ws",
            directToken = "ambiguous-token",
        )
        val unrelated = WorkspaceConnection(
            id = "unrelated",
            baseUrl = "https://unrelated.example.com/workspaces/ws",
            directToken = "unrelated-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(first, second),
            connections = listOf(ambiguous, unrelated),
        )

        assertEquals(listOf(ambiguous, unrelated), replaced.connections)
    }

    @Test fun replacing_a_relay_account_considers_non_retained_legacy_host_owners() {
        val sharedOldUrl = "https://shared.relay.example.com"
        val retained = host.copy(
            hostId = "h-retained",
            publicUrl = "https://retained.relay.example.com",
            directUrl = "http://retained.lan:47190",
            legacyPublicUrls = listOf(sharedOldUrl),
        )
        val removed = host.copy(
            hostId = "h-removed",
            publicUrl = "https://removed.relay.example.com",
            directUrl = null,
            legacyPublicUrls = listOf(sharedOldUrl),
        )
        val ambiguous = WorkspaceConnection(
            id = "ambiguous-retention",
            baseUrl = "$sharedOldUrl/workspaces/ws",
            directToken = "standing-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(retained, removed),
            connections = listOf(ambiguous),
        )

        assertEquals(listOf(ambiguous), replaced.connections)
    }

    @Test fun replacing_a_relay_account_preserves_cross_relay_base_collision() {
        val sharedBase = "https://shared.relay.example.com"
        val previousHost = host.copy(
            hostId = "previous-host",
            publicUrl = "https://previous.relay.example.com",
            directUrl = "http://previous.lan:47190",
            legacyPublicUrls = listOf(sharedBase),
        )
        val otherRelayHost = host.copy(
            hostId = "other-host",
            publicUrl = "https://other.relay.example.com",
            relayDomain = "other.example.com",
            legacyPublicUrls = listOf(sharedBase),
        )
        val ambiguous = WorkspaceConnection(
            id = "ambiguous",
            baseUrl = "$sharedBase/workspaces/ws",
            directToken = "standing-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(previousHost, otherRelayHost),
            connections = listOf(ambiguous),
        )

        assertEquals(listOf(ambiguous), replaced.connections)
    }

    @Test fun absent_host_evidence_survives_account_replacement_with_direct_and_manual_rows() {
        val directHost = HostConnection(
            hostId = "direct-host",
            directUrl = "http://direct.lan:47190",
        )
        val orphan = WorkspaceConnection(
            id = "orphan",
            baseUrl = "https://removed.relay.example.com/workspaces/ws",
            hostId = "removed-relay-host",
            workspaceId = "ws",
        )
        val viableDirect = WorkspaceConnection(
            id = "direct",
            baseUrl = "${directHost.directUrl}/workspaces/ws",
            token = "direct-token",
            hostId = directHost.hostId,
            workspaceId = "ws",
            directToken = "direct-token",
        )
        val manual = WorkspaceConnection(
            id = "manual",
            baseUrl = "http://manual.lan:47100",
            token = "manual-token",
        )

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("new.example.com", "new-token"),
            hosts = listOf(directHost),
            connections = listOf(orphan, viableDirect, manual),
        )

        assertEquals(listOf("orphan", "direct", "manual"), replaced.connections.map { it.id })
        assertEquals(listOf(directHost), replaced.hosts)
    }

    @Test fun replacing_a_same_domain_relay_token_clears_the_previous_fleet() {
        val oldWorkspace = WorkspaceConnection(
            id = "old",
            baseUrl = "${host.publicUrl}/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val manualWorkspace = WorkspaceConnection(id = "manual", baseUrl = "http://lan")

        val replaced = replaceRelayAccountFleet(
            previous = RelayAccount("relay.example.com", "old-token"),
            replacement = RelayAccount("relay.example.com", "new-token"),
            hosts = listOf(host),
            connections = listOf(oldWorkspace, manualWorkspace),
        )

        assertEquals(emptyList<HostConnection>(), replaced.hosts)
        assertEquals(listOf("manual"), replaced.connections.map { it.id })
    }


    @Test fun authoritative_directory_removal_also_removes_the_hosts_workspaces() {
        val removedHost = host.copy(
            hostId = "h-removed",
            publicUrl = "https://removed.relay.example.com",
        )
        val otherHost = host.copy(hostId = "h-other", relayDomain = "other.example.com")
        val retainedWorkspace = WorkspaceConnection(
            id = "retained",
            baseUrl = "https://retained/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val removedWorkspace = retainedWorkspace.copy(
            id = "removed",
            baseUrl = "${removedHost.publicUrl}/workspaces/ws",
            hostId = removedHost.hostId,
        )
        val otherWorkspace = retainedWorkspace.copy(
            id = "other",
            baseUrl = "${otherHost.publicUrl}/workspaces/ws",
            hostId = otherHost.hostId,
        )
        val manualWorkspace = WorkspaceConnection(id = "manual", baseUrl = "http://lan")

        val replaced = replaceRelayDirectoryFleet(
            relayDomain = "relay.example.com",
            existingHosts = listOf(host, removedHost, otherHost),
            replacementHosts = listOf(host),
            connections = listOf(
                retainedWorkspace,
                removedWorkspace,
                otherWorkspace,
                manualWorkspace,
            ),
        )

        assertEquals(listOf("h-other", "h-1"), replaced.hosts.map { it.hostId })
        assertEquals(listOf("retained", "other", "manual"), replaced.connections.map { it.id })
    }

    @Test fun direct_discovery_persists_the_runner_observation_on_the_host() {
        val now = 100_000L
        val stored = recordDirectHostDiscovery(
            hosts = emptyList(),
            hostId = "h-direct",
            directUrl = "http://192.168.1.9:47190",
            runnerState = "idle",
            contactedAtMillis = now,
        ).single()
        val connection = WorkspaceConnection(
            id = "ws-direct",
            baseUrl = "${stored.directUrl}/workspaces/ws-1",
            workspaceName = "workspace",
            hostId = stored.hostId,
            workspaceId = "ws-1",
            state = "healthy",
        )

        assertEquals("idle", stored.runnerState)
        assertEquals(HostLadderState.ACTIVE, ladderFor(connection, stored, now))
    }

    @Test fun delayed_direct_discovery_cannot_move_contact_freshness_backwards() {
        val existing = host.copy(lastContactAtMillis = 200L)

        val updated = recordDirectHostDiscovery(
            hosts = listOf(existing),
            hostId = existing.hostId,
            directUrl = "http://192.168.1.9:47190",
            runnerState = "idle",
            contactedAtMillis = 100L,
        ).single()

        assertEquals(200L, updated.lastContactAtMillis)
    }

    @Test fun rotating_a_direct_url_retains_one_normalized_legacy_identity() {
        val existing = HostConnection(
            hostId = "direct-host",
            directUrl = "HTTP://OLD.DIRECT.EXAMPLE/",
            legacyPublicUrls = listOf(
                "https://OLD.RELAY.EXAMPLE/",
                "https://old.relay.example",
            ),
        )

        val updated = recordDirectHostDiscovery(
            hosts = listOf(existing),
            hostId = existing.hostId,
            directUrl = "http://new.direct.example/",
            runnerState = null,
            contactedAtMillis = 100L,
        ).single()

        assertEquals("http://new.direct.example", updated.directUrl)
        assertEquals(
            listOf("https://old.relay.example", "http://old.direct.example"),
            updated.legacyPublicUrls,
        )
    }

    @Test fun old_persisted_state_without_the_hosts_key_decodes_to_empty() {
        // The key is absent on every install that predates #471.
        assertEquals(emptyList<HostConnection>(), HostsCodec.decode(""))
        assertEquals(emptyList<HostConnection>(), HostsCodec.decode("not json"))
    }

    @Test fun stored_json_without_the_newer_fields_still_decodes() {
        // Defaults, not ignoreUnknownKeys — missing keys are covered only by
        // defaults (the back-compat lesson pinned in HostWorkspacesTest).
        val old = """[{"hostId":"h-1","publicUrl":"https://h.example.com"}]"""
        val decoded = HostsCodec.decode(old)
        assertEquals(1, decoded.size)
        assertNull(decoded[0].refresh)
        assertNull(decoded[0].directUrl)
        assertTrue(decoded[0].label.isEmpty())
    }
    @Test fun duplicate_host_ingress_rejects_before_any_pure_replacement() = runTest {
        val first = HostConnection(hostId = "duplicate", publicUrl = "https://one.test")
        val last = HostConnection(hostId = "duplicate", publicUrl = "https://two.test")
        assertIllegalArgument {
            replaceRelayDirectoryFleet(
                "relay.example",
                listOf(first, last),
                listOf(host),
                emptyList(),
            )
        }
        assertIllegalArgument {
            replaceRelayDirectoryFleet(
                "relay.example",
                listOf(host),
                listOf(first, last),
                emptyList(),
            )
        }
        assertIllegalArgument {
            replaceRelayDirectoryFleet(
                "relay.example",
                listOf(host),
                listOf(
                    HostConnection("pending:request-1", publicUrl = "https://one.test"),
                    HostConnection("pending:request-1", publicUrl = "https://two.test"),
                ),
                emptyList(),
            )
        }
        assertIllegalArgument {
            replaceRelayAccountFleet(
                RelayAccount("old.example", "old-token"),
                RelayAccount("new.example", "new-token"),
                listOf(first.copy(relayDomain = "old.example"), last.copy(relayDomain = "old.example")),
                emptyList(),
            )
        }
        assertEquals(
            listOf("first", "second"),
            replaceRelayDirectoryFleet(
                "relay.example",
                listOf(host.copy(relayDomain = "relay.example")),
                listOf(
                    HostConnection("first", publicUrl = "https://one.test"),
                    HostConnection("second", publicUrl = "https://two.test"),
                ),
                emptyList(),
            ).hosts.map { it.hostId },
        )
    }

    @Test fun replacement_directory_rejects_a_host_id_owned_by_a_different_relay() = runTest {
        assertIllegalArgument {
            replaceRelayDirectoryFleet(
                relayDomain = "replacement.example",
                existingHosts = listOf(
                    HostConnection(
                        hostId = "shared-host",
                        publicUrl = "https://other.example/host",
                        relayDomain = "other.example",
                    ),
                ),
                replacementHosts = listOf(
                    HostConnection(
                        hostId = "shared-host",
                        publicUrl = "https://replacement.example/host",
                        relayDomain = "replacement.example",
                    ),
                ),
                connections = emptyList(),
            )
        }
    }

    @Test fun duplicate_host_ingress_keeps_persisted_bytes_unchanged() = runTest {
        val dataStore = newDataStore("duplicate-hosts.preferences_pb", backgroundScope)
        val oldAccount = RelayAccount("old.example", "old-token")
        val originalConnection = WorkspaceConnection(
            "ws",
            "https://old.test/workspaces/ws",
            token = "secret",
        )
        dataStore.edit {
            it[hostsKey] = HostsCodec.encode(
                listOf(
                    HostConnection("duplicate", publicUrl = "https://one.test", relayDomain = oldAccount.relayDomain),
                    HostConnection("duplicate", publicUrl = "https://two.test", relayDomain = oldAccount.relayDomain),
                ),
            )
            it[connectionsKey] = ConnectionsCodec.encode(listOf(originalConnection))
            it[relayDomainKey] = oldAccount.relayDomain
            it[relayFleetTokenKey] = oldAccount.fleetToken
            it[generationKey] = 23L
        }
        val repository = HostsRepository(dataStore)
        val before = rawRouteOwnershipState(dataStore)
        assertIllegalArgument {
            repository.setRelayAccount(RelayAccount("new.example", "new-token"))
        }
        assertEquals(before, rawRouteOwnershipState(dataStore))
        val directoryStore = newDataStore("duplicate-directory.preferences_pb", backgroundScope)
        val directoryAccount = RelayAccount("relay.example", "fleet-token")
        directoryStore.edit {
            it[hostsKey] = HostsCodec.encode(
                listOf(HostConnection("stable", publicUrl = "https://stable.test", relayDomain = directoryAccount.relayDomain)),
            )
            it[connectionsKey] = ConnectionsCodec.encode(listOf(originalConnection))
            it[relayDomainKey] = directoryAccount.relayDomain
            it[relayFleetTokenKey] = directoryAccount.fleetToken
            it[generationKey] = 17L
        }
        val directoryRepository = HostsRepository(directoryStore)
        val directoryBefore = rawRouteOwnershipState(directoryStore)
        assertIllegalArgument {
            directoryRepository.replaceFromRelay(
                directoryAccount,
                17L,
                listOf(
                    HostConnection("duplicate", publicUrl = "https://one.test"),
                    HostConnection("duplicate", publicUrl = "https://two.test"),
                ),
            )
        }
        assertEquals(directoryBefore, rawRouteOwnershipState(directoryStore))
    }
    @Test fun ownership_mismatch_or_absent_candidate_preserves_connections() {
        val account = RelayAccount("relay.example", "fleet-token")
        val owner = HostConnection(
            hostId = "host-1",
            publicUrl = "https://host.example/root",
            relayDomain = account.relayDomain,
            directUrl = "https://direct.example/root",
        )
        val conflicts = listOf(
            WorkspaceConnection(
                "wrong-host",
                "https://host.example/root/workspaces/ws-1",
                token = "standing-secret",
                directToken = "direct-secret",
                hostId = "other-host",
                workspaceId = "ws-1",
            ),
            WorkspaceConnection(
                "wrong-workspace",
                "https://host.example/root/workspaces/ws-1",
                token = "standing-secret",
                directToken = "direct-secret",
                hostId = owner.hostId,
                workspaceId = "other-workspace",
            ),
        )
        assertEquals(
            conflicts,
            replaceRelayDirectoryFleet(
                account.relayDomain,
                listOf(owner),
                emptyList(),
                conflicts,
            ).connections,
        )
        assertEquals(
            conflicts,
            replaceRelayAccountFleet(
                account,
                RelayAccount("new.example", "new-token"),
                listOf(owner),
                conflicts,
            ).connections,
        )
        val unrelated = HostConnection(
            hostId = "other",
            publicUrl = "https://other.example/root",
            relayDomain = account.relayDomain,
        )
        assertEquals(
            conflicts,
            replaceRelayDirectoryFleet(
                account.relayDomain,
                listOf(unrelated),
                emptyList(),
                conflicts,
            ).connections,
        )
    }
    @Test fun encoded_dot_route_cannot_authorize_directory_removal_or_account_migration() {
        val previous = RelayAccount("relay.example", "old-token")
        val owner = HostConnection(
            hostId = "host-1",
            publicUrl = "https://host.example/root",
            directUrl = "https://direct.example/root",
            relayDomain = previous.relayDomain,
        )
        val encodedDot = WorkspaceConnection(
            id = "legacy",
            baseUrl = "https://host.example/root/workspaces/%2e%2e",
            token = "standing-secret",
            directToken = "direct-secret",
        )

        assertEquals(
            listOf(encodedDot),
            replaceRelayDirectoryFleet(
                previous.relayDomain,
                listOf(owner),
                emptyList(),
                listOf(encodedDot),
            ).connections,
        )
        assertEquals(
            listOf(encodedDot),
            replaceRelayAccountFleet(
                previous,
                RelayAccount("new.example", "new-token"),
                listOf(owner),
                listOf(encodedDot),
            ).connections,
        )
    }
    @Test fun URL_valued_host_id_for_a_different_candidate_blocks_account_migration() {
        val previous = RelayAccount("relay.example", "old-token")
        val owner = HostConnection(
            hostId = "host-a",
            publicUrl = "https://host-a.test/root",
            directUrl = "https://direct-a.test/root",
            relayDomain = previous.relayDomain,
        )
        val conflictingHost = HostConnection(
            hostId = "host-b",
            publicUrl = "https://host-b.test/root",
            relayDomain = previous.relayDomain,
        )
        val connection = WorkspaceConnection(
            id = "legacy",
            baseUrl = "https://host-a.test/root/workspaces/ws-1",
            token = "standing-secret",
            directToken = "direct-secret",
            hostId = conflictingHost.publicUrl,
        )

        assertEquals(
            listOf(connection),
            replaceRelayAccountFleet(
                previous,
                RelayAccount("new.example", "new-token"),
                listOf(owner, conflictingHost),
                listOf(connection),
            ).connections,
        )
    }
}
