package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.notify.DeepLinkOutcome
import com.atomikpanda.groundcontrol.notify.DeepLinkResolver
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkResolverTest {
    private val conns = listOf(
        WorkspaceConnection("c1", "http://host:47100", "tok", "Work"),
        WorkspaceConnection("c2", "https://relay.example.com", null, "Relay"),
    )
    private fun uri(workspace: String, id: String) =
        "groundcontrol://thread?workspace=${URLEncoder.encode(workspace, "UTF-8")}&id=$id"

    @Test fun resolves_known_workspace_by_base_url() {
        assertEquals(DeepLinkOutcome.OpenThread("c1", "t1"), DeepLinkResolver.resolve(uri("http://host:47100", "t1"), conns))
    }

    @Test fun resolves_by_base_url_ignoring_trailing_slash() {
        assertEquals(DeepLinkOutcome.OpenThread("c1", "t9"), DeepLinkResolver.resolve(uri("http://host:47100/", "t9"), conns))
    }

    @Test fun falls_back_to_workspace_name() {
        assertEquals(DeepLinkOutcome.OpenThread("c2", "t2"), DeepLinkResolver.resolve(uri("Relay", "t2"), conns))
    }

    @Test fun unknown_workspace_routes_to_add_connection() {
        assertTrue(DeepLinkResolver.resolve(uri("http://nope:1", "t3"), conns) is DeepLinkOutcome.AddConnection)
    }

    @Test fun malformed_uri_is_ignored() {
        assertEquals(DeepLinkOutcome.Ignore, DeepLinkResolver.resolve("groundcontrol://thread?id=t1", conns))
        assertEquals(DeepLinkOutcome.Ignore, DeepLinkResolver.resolve("groundcontrol://other?x=1", conns))
        assertEquals(DeepLinkOutcome.Ignore, DeepLinkResolver.resolve("not a uri", conns))
    }
}
