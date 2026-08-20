package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.HostsCodec
import com.atomikpanda.groundcontrol.data.hostBase
import com.atomikpanda.groundcontrol.data.hostFrom
import com.atomikpanda.groundcontrol.data.ladderFor
import com.atomikpanda.groundcontrol.data.markRelayUnreachable
import com.atomikpanda.groundcontrol.data.replaceRelayHosts
import com.atomikpanda.groundcontrol.data.replaceRelayDirectoryFleet
import com.atomikpanda.groundcontrol.data.replaceRelayAccountFleet
import com.atomikpanda.groundcontrol.data.recordDirectHostDiscovery
import com.atomikpanda.groundcontrol.data.relayAccountMatchesExpected
import com.atomikpanda.groundcontrol.data.upsertHost
import com.atomikpanda.groundcontrol.data.upsertConnection
import com.atomikpanda.groundcontrol.data.dto.HostInfo
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
