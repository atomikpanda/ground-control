package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.HostsCodec
import com.atomikpanda.groundcontrol.data.hostBase
import com.atomikpanda.groundcontrol.data.markRelayUnreachable
import com.atomikpanda.groundcontrol.data.replaceRelayHosts
import com.atomikpanda.groundcontrol.data.replaceRelayAccountFleet
import com.atomikpanda.groundcontrol.data.upsertHost
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
        // AC9: a host reachable on LAN stays usable while the relay is down, which
        // is only true if the refresh credential survives a process death — it is
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

    @Test fun a_reachable_direct_url_is_preferred_over_the_relay() {
        assertEquals("https://h-1abc.relay.example.com", host.hostBase())
        assertEquals("http://192.168.1.9:47190", host.copy(directUrl = "http://192.168.1.9:47190/").hostBase())
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
