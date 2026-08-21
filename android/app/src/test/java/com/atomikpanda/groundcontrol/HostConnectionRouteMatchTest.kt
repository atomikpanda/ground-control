package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.knownHostsForLegacyConnection
import com.atomikpanda.groundcontrol.data.hasKnownBaseIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class HostConnectionRouteMatchTest {
    @Test fun stale_relay_direct_url_does_not_make_a_direct_only_host_ambiguous() {
        val direct = "http://shared.lan:47190"
        val relayHostWithStaleDirect = HostConnection(
            hostId = "relay",
            publicUrl = "https://relay.example",
            refresh = "relay-refresh",
            directUrl = direct,
        )
        val directOnlyHost = HostConnection(hostId = "direct", directUrl = direct)

        val matches = listOf(relayHostWithStaleDirect, directOnlyHost)
            .filter { it.hasKnownBaseIdentity(direct) }

        assertEquals(listOf(directOnlyHost), matches)
    }

    @Test fun unique_legacy_public_url_remains_a_known_route_when_no_current_base_matches() {
        val legacy = "https://retired.relay.example"
        val host = HostConnection(
            hostId = "relay",
            publicUrl = "https://current.relay.example",
            refresh = "relay-refresh",
            legacyPublicUrls = listOf(legacy),
        )

        assertEquals(listOf(host), listOf(host).filter { it.hasKnownBaseIdentity(legacy) })
    }

    @Test fun legacy_workspace_route_prefers_a_current_direct_host_over_a_relay_alias() {
        val retiredDirect = "http://shared.lan:47190"
        val relayHost = HostConnection(
            hostId = "relay",
            publicUrl = "https://relay.example",
            refresh = "relay-refresh",
            directUrl = "http://current.lan:47190",
            legacyPublicUrls = listOf(retiredDirect),
        )
        val directOnlyHost = HostConnection(hostId = "direct", directUrl = retiredDirect)
        val legacyWorkspace = WorkspaceConnection(
            id = "legacy",
            baseUrl = "$retiredDirect/workspaces/workspace",
            hostId = retiredDirect,
        )

        assertEquals(
            listOf(directOnlyHost),
            knownHostsForLegacyConnection(legacyWorkspace, listOf(relayHost, directOnlyHost)),
        )
    }
}
