package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalConnectionRoutesTest {
    private val adopted = WorkspaceConnection(
        id = "canonical",
        baseUrl = "https://relay.example/hosts/host-1/workspaces/ws-1",
        legacyConnectionIds = listOf("retired"),
    )

    @Test fun resolved_connection_routes_always_embed_the_canonical_handle() {
        assertEquals(
            "thread/canonical/thread-1",
            connectionRoute(adopted, "thread", "thread-1"),
        )
        assertEquals(
            "specDetail/canonical/spec-1",
            connectionRoute(adopted, "specDetail", "spec-1"),
        )
    }

    @Test fun farm_and_item_redirects_share_canonical_route_selection() {
        val running = WorkItemSummary(
            id = "item-1",
            kind = "feature",
            title = "Running",
            phase = "in_flight",
        )
        val spec = WorkItemSummary(
            id = "item-2",
            kind = "feature",
            title = "Spec",
            phase = "inbox",
            specId = "spec-1",
        )
        val task = WorkItemSummary(
            id = "item-3",
            kind = "feature",
            title = "Task",
            phase = "inbox",
            taskSlugs = listOf("task-1"),
        )
        val thread = WorkItemSummary(
            id = "item-4",
            kind = "feature",
            title = "Thread",
            phase = "inbox",
            threadIds = listOf("thread-1"),
        )

        assertEquals("console/canonical/item-1", workItemRoute(adopted, running))
        assertEquals("specDetail/canonical/spec-1", workItemRoute(adopted, spec))
        assertEquals("taskDetail/canonical/task-1", workItemRoute(adopted, task))
        assertEquals("thread/canonical/thread-1", workItemRoute(adopted, thread))
    }
}
