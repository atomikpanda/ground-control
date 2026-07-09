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
    private fun uri(workspace: String, id: String) = uri("thread", workspace, id)
    private fun uri(host: String, workspace: String, id: String) =
        "groundcontrol://$host?workspace=${URLEncoder.encode(workspace, "UTF-8")}&id=$id"

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

    @Test fun resolves_item_host_by_base_url() {
        assertEquals(
            DeepLinkOutcome.OpenItem("c1", "wi-x"),
            DeepLinkResolver.resolve(uri("item", "http://host:47100", "wi-x"), conns),
        )
    }

    @Test fun resolves_spec_host_by_base_url() {
        assertEquals(
            DeepLinkOutcome.OpenSpec("c1", "s1"),
            DeepLinkResolver.resolve(uri("spec", "http://host:47100", "s1"), conns),
        )
    }

    @Test fun resolves_task_host_by_base_url() {
        assertEquals(
            DeepLinkOutcome.OpenTask("c1", "k1"),
            DeepLinkResolver.resolve(uri("task", "http://host:47100", "k1"), conns),
        )
    }

    @Test fun entity_hosts_route_to_add_connection_for_unknown_workspace() {
        assertTrue(DeepLinkResolver.resolve(uri("item", "http://nope:1", "wi-x"), conns) is DeepLinkOutcome.AddConnection)
        assertTrue(DeepLinkResolver.resolve(uri("spec", "http://nope:1", "s1"), conns) is DeepLinkOutcome.AddConnection)
        assertTrue(DeepLinkResolver.resolve(uri("task", "http://nope:1", "k1"), conns) is DeepLinkOutcome.AddConnection)
    }

    @Test fun entity_hosts_missing_or_blank_id_is_ignored() {
        assertEquals(
            DeepLinkOutcome.Ignore,
            DeepLinkResolver.resolve("groundcontrol://item?workspace=http://host:47100", conns),
        )
        assertEquals(
            DeepLinkOutcome.Ignore,
            DeepLinkResolver.resolve(uri("spec", "http://host:47100", ""), conns),
        )
        assertEquals(
            DeepLinkOutcome.Ignore,
            DeepLinkResolver.resolve("groundcontrol://task?workspace=http://host:47100&id=", conns),
        )
    }
}
