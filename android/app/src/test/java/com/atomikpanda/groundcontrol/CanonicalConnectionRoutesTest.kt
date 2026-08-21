package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalConnectionRoutesTest {
    private val connection = WorkspaceConnection("workspace-a", "https://host/workspaces/a", "token", "A")

    @Test fun connection_route_uses_the_current_connection_id() {
        assertEquals("thread/workspace-a/thread-1", connectionRoute(connection, "thread", "thread-1"))
    }

    @Test fun new_thread_route_preserves_a_current_workspace_selection() {
        assertEquals("newThread?connectionId=workspace-a", newThreadRoute(connection.id))
        assertEquals("newThread", newThreadRoute(null))
    }

    @Test fun route_helpers_do_not_embed_connection_credentials() {
        assertTrue(connectionRoute(connection, "workspace", "overview").contains(connection.id))
        assertTrue(!connectionRoute(connection, "workspace", "overview").contains(connection.token.orEmpty()))
    }
}
