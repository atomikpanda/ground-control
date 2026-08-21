package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.LegacyRouteOwnership
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.hasKnownBaseIdentity
import com.atomikpanda.groundcontrol.data.legacyRouteOwnership
import org.junit.Assert.assertEquals
import org.junit.Test

class HostConnectionRouteMatchTest {
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

    @Test fun shared_base_claimed_by_relay_alias_and_direct_host_is_ambiguous() {
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
            LegacyRouteOwnership.Ambiguous,
            legacyRouteOwnership(legacyWorkspace, listOf(relayHost, directOnlyHost)),
        )
    }
}
