package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
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
}
