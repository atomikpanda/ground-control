package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.HostsCodec
import com.atomikpanda.groundcontrol.data.hostBase
import com.atomikpanda.groundcontrol.data.directRefreshGeneration
import com.atomikpanda.groundcontrol.data.hostFrom
import com.atomikpanda.groundcontrol.data.ladderFor
import com.atomikpanda.groundcontrol.data.markRelayUnreachable
import com.atomikpanda.groundcontrol.data.matchesWorkspaceRefreshRoute
import com.atomikpanda.groundcontrol.data.replaceRelayHosts
import com.atomikpanda.groundcontrol.data.replaceRelayDirectoryFleet
import com.atomikpanda.groundcontrol.data.replaceRelayAccountFleet
import com.atomikpanda.groundcontrol.data.recordDirectHostDiscovery
import com.atomikpanda.groundcontrol.data.relayAccountMatchesExpected
import com.atomikpanda.groundcontrol.data.upsertHost
import com.atomikpanda.groundcontrol.data.upsertConnection
import com.atomikpanda.groundcontrol.data.workspaceRefreshSourceStillCurrent
import com.atomikpanda.groundcontrol.data.dto.HostInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host model persisted under the "hosts" DataStore key (#471). */
class HostsRepositoryTest {
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
    @Test fun an_in_flight_direct_refresh_is_rejected_after_a_new_route_and_credential() {
        val oldBase = "http://old.lan:47190"
        val newBase = "http://new.lan:47190"
        val inFlightHost = host.copy(
            publicUrl = "",
            refresh = null,
            directUrl = oldBase,
            relayDomain = null,
        )
        val currentHost = recordDirectHostDiscovery(
            hosts = listOf(inFlightHost),
            hostId = host.hostId,
            directUrl = newBase,
            runnerState = null,
            contactedAtMillis = 2L,
        ).single()
        val currentConnection = upsertConnection(
            existing = listOf(
                WorkspaceConnection(
                    id = "workspace",
                    baseUrl = "$oldBase/workspaces/ws",
                    token = "old-token",
                ),
            ),
            conn = WorkspaceConnection(
                id = "workspace",
                baseUrl = "$newBase/workspaces/ws",
                token = "new-token",
            ),
            preservePriorDirectToken = false,
        ).single()

        assertEquals(newBase, currentHost.directUrl)
        assertEquals("new-token", currentConnection.token)
        assertFalse(
            currentHost.matchesWorkspaceRefreshRoute(
                hostBase = oldBase,
                expectedDirectOnly = true,
            ),
        )
        assertFalse(
            currentHost.copy(
                publicUrl = oldBase,
                refresh = "new-refresh",
            ).matchesWorkspaceRefreshRoute(
                hostBase = oldBase,
                expectedDirectOnly = true,
            ),
        )
    }
    @Test fun an_in_flight_direct_refresh_is_rejected_after_same_base_re_pair() {
        val base = "http://direct.lan:47190"
        val directHost = host.copy(
            publicUrl = "",
            refresh = null,
            directUrl = base,
            relayDomain = null,
        )
        val oldConnection = WorkspaceConnection(
            id = "workspace",
            baseUrl = "$base/workspaces/ws",
            token = "old-token",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val expected = directRefreshGeneration(
            connections = listOf(oldConnection),
            hostId = host.hostId,
            hosts = listOf(directHost),
            identities = emptyList(),
        )
        val rePaired = upsertConnection(
            existing = listOf(oldConnection),
            conn = oldConnection.copy(
                token = "new-token",
                directToken = null,
            ),
            preservePriorDirectToken = false,
        )

        assertEquals("old-token", expected?.credential)
        assertTrue(
            workspaceRefreshSourceStillCurrent(
                currentHost = directHost,
                currentHosts = listOf(directHost),
                currentConnections = listOf(oldConnection),
                expectedAccount = RelayAccount("relay.example.com", "fleet-token"),
                hostBase = base,
                expectedDirectOnly = true,
                expectedRelayRefresh = null,
                expectedDirectGeneration = expected,
                identities = emptyList(),
            ),
        )
        assertFalse(
            workspaceRefreshSourceStillCurrent(
                currentHost = directHost,
                currentHosts = listOf(directHost),
                currentConnections = rePaired,
                expectedAccount = RelayAccount("relay.example.com", "fleet-token"),
                hostBase = base,
                expectedDirectOnly = true,
                expectedRelayRefresh = null,
                expectedDirectGeneration = expected,
                identities = emptyList(),
            ),
        )
        assertEquals("new-token", rePaired.single().token)
        assertEquals("new-token", rePaired.single().directToken)
    }

    @Test fun legacy_direct_identity_migration_invalidates_its_captured_generation() {
        val oldBase = "http://old.direct.example"
        val currentBase = "http://current.direct.example"
        val directHost = HostConnection(
            hostId = "direct-host",
            directUrl = currentBase,
            legacyPublicUrls = listOf(oldBase),
        )
        val legacySource = WorkspaceConnection(
            id = "legacy-source",
            baseUrl = "$oldBase/workspaces/ws",
            token = "direct-token",
            hostId = oldBase,
            workspaceId = "ws",
        )
        val identities = listOf(
            VerifiedIdentity(legacySource.id, directHost.hostId, "ws"),
        )
        val expected = directRefreshGeneration(
            connections = listOf(legacySource),
            hostId = directHost.hostId,
            hosts = listOf(directHost),
            identities = identities,
        )
        val migrated = legacySource.copy(
            baseUrl = "$currentBase/workspaces/ws",
            hostId = directHost.hostId,
        )

        assertTrue(
            workspaceRefreshSourceStillCurrent(
                currentHost = directHost,
                currentHosts = listOf(directHost),
                currentConnections = listOf(legacySource),
                expectedAccount = RelayAccount("relay.example.com", "fleet-token"),
                hostBase = currentBase,
                expectedDirectOnly = true,
                expectedRelayRefresh = null,
                expectedDirectGeneration = expected,
                identities = identities,
            ),
        )
        assertFalse(
            workspaceRefreshSourceStillCurrent(
                currentHost = directHost,
                currentHosts = listOf(directHost),
                currentConnections = listOf(migrated),
                expectedAccount = RelayAccount("relay.example.com", "fleet-token"),
                hostBase = currentBase,
                expectedDirectOnly = true,
                expectedRelayRefresh = null,
                expectedDirectGeneration = expected,
                identities = identities,
            ),
        )
    }

    @Test fun an_in_flight_relay_refresh_is_rejected_after_credential_rotation() {
        val account = RelayAccount("relay.example.com", "fleet-token")
        val snapshot = host.copy(refresh = "old-refresh")
        val rotated = snapshot.copy(refresh = "new-refresh")

        assertTrue(
            workspaceRefreshSourceStillCurrent(
                currentHost = snapshot,
                currentHosts = listOf(snapshot),
                currentConnections = emptyList(),
                expectedAccount = account,
                hostBase = snapshot.publicUrl,
                expectedDirectOnly = false,
                expectedRelayRefresh = "old-refresh",
                expectedDirectGeneration = null,
                identities = emptyList(),
            ),
        )
        assertFalse(
            workspaceRefreshSourceStillCurrent(
                currentHost = rotated,
                currentHosts = listOf(rotated),
                currentConnections = emptyList(),
                expectedAccount = account,
                hostBase = snapshot.publicUrl,
                expectedDirectOnly = false,
                expectedRelayRefresh = "old-refresh",
                expectedDirectGeneration = null,
                identities = emptyList(),
            ),
        )
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


    @Test fun replacing_a_relay_account_clears_only_the_previous_fleet() {
        val otherHost = host.copy(hostId = "h-2", relayDomain = "other.example.com")
        val oldWorkspace = WorkspaceConnection(
            id = "old",
            baseUrl = "https://old/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val otherWorkspace = oldWorkspace.copy(id = "other", hostId = otherHost.hostId)
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
            directToken = "null-token",
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
        assertEquals("null-token", restoredById.getValue("null-host").token)
        assertEquals("null-token", restoredById.getValue("null-host").directToken)
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

    @Test fun replacing_a_same_domain_relay_token_clears_the_previous_fleet() {
        val oldWorkspace = WorkspaceConnection(
            id = "old",
            baseUrl = "https://old/workspaces/ws",
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

    @Test fun stale_refreshes_do_not_match_a_replaced_relay_account() {
        val expected = RelayAccount("relay.example.com", "old-token")

        assertTrue(relayAccountMatchesExpected(expected, expected))
        assertTrue(
            !relayAccountMatchesExpected(
                RelayAccount("relay.example.com", "new-token"),
                expected,
            ),
        )
        assertTrue(
            !relayAccountMatchesExpected(
                RelayAccount("new.example.com", "old-token"),
                expected,
            ),
        )
    }

    @Test fun authoritative_directory_removal_also_removes_the_hosts_workspaces() {
        val removedHost = host.copy(hostId = "h-removed")
        val otherHost = host.copy(hostId = "h-other", relayDomain = "other.example.com")
        val retainedWorkspace = WorkspaceConnection(
            id = "retained",
            baseUrl = "https://retained/workspaces/ws",
            hostId = host.hostId,
            workspaceId = "ws",
        )
        val removedWorkspace = retainedWorkspace.copy(id = "removed", hostId = removedHost.hostId)
        val otherWorkspace = retainedWorkspace.copy(id = "other", hostId = otherHost.hostId)
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
}
