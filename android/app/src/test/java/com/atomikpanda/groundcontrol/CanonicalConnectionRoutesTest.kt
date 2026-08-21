package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
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

    @Test fun replacing_a_connection_rebinds_the_route_and_changes_its_view_model_key() {
        val original = adopted.copy(
            baseUrl = "https://direct.example",
            token = "old-token",
        )
        val replacement = original.copy(
            baseUrl = "https://relay.example/hosts/host-2/workspaces/ws-1",
            token = "new-token",
        )

        val originalBinding = connectionRouteBinding(
            connections = listOf(original),
            connectionId = original.id,
            destinationKey = "detail-spec-1",
        )!!
        val replacementBinding = connectionRouteBinding(
            connections = listOf(replacement),
            connectionId = replacement.id,
            destinationKey = "detail-spec-1",
        )!!

        assertEquals(replacement, replacementBinding.connection)
        assertNotEquals(originalBinding.viewModelKey, replacementBinding.viewModelKey)
    }

    @Test fun route_binding_is_stable_for_the_same_connection_and_disappears_when_removed() {
        val first = connectionRouteBinding(
            connections = listOf(adopted),
            connectionId = adopted.id,
            destinationKey = "thread-thread-1",
        )
        val recomposed = connectionRouteBinding(
            connections = listOf(adopted.copy()),
            connectionId = adopted.id,
            destinationKey = "thread-thread-1",
        )

        assertEquals(first, recomposed)
        assertNull(
            connectionRouteBinding(
                connections = emptyList(),
                connectionId = adopted.id,
                destinationKey = "thread-thread-1",
            ),
        )
    }

    @Test fun colliding_connection_hashes_still_rekey_the_route_binding() {
        val first = adopted.copy(baseUrl = "https://relay.example/Aa")
        val replacement = adopted.copy(baseUrl = "https://relay.example/BB")
        assertEquals(first.hashCode(), replacement.hashCode())

        val firstBinding = connectionRouteBinding(listOf(first), first.id, "detail-spec-1")!!
        val replacementBinding = connectionRouteBinding(listOf(replacement), replacement.id, "detail-spec-1")!!

        assertNotEquals(firstBinding.viewModelKey, replacementBinding.viewModelKey)
    }

    @Test fun item_redirect_rethrows_cancelled_fetch() {
        val cancellation = CancellationException("connection replaced")

        try {
            runCatching<Unit> { throw cancellation }.getOrNullOrRethrowCancellation()
            fail("CancellationException must propagate")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
        }
    }

}
