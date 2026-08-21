package com.atomikpanda.groundcontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun metadata_only_connection_changes_keep_the_route_view_model_key() {
        val original = adopted.copy(
            token = "credential",
            hostId = "host-1",
            workspaceId = "ws-1",
        )
        val metadataOnly = original.copy(
            workspaceName = "Renamed workspace",
            colorOverride = "#FF00FF00",
            glyphOverride = "W",
            state = "offline",
            legacyBaseUrls = listOf("https://old.example/workspaces/ws-1"),
            legacyConnectionIds = listOf("old-id"),
            directToken = "retained-direct-credential",
        )

        val originalKey = connectionRouteBinding(listOf(original), original.id, "detail-spec-1")!!.viewModelKey
        val metadataKey = connectionRouteBinding(listOf(metadataOnly), metadataOnly.id, "detail-spec-1")!!.viewModelKey

        assertEquals(originalKey, metadataKey)
    }

    @Test fun endpoint_auth_and_host_route_changes_rekey_the_route_view_model() {
        val original = adopted.copy(
            token = "credential",
            hostId = "host-1",
            workspaceId = "ws-1",
        )
        val originalKey = connectionRouteBinding(listOf(original), original.id, "detail-spec-1")!!.viewModelKey

        listOf(
            original.copy(baseUrl = "https://relay.example/hosts/host-2/workspaces/ws-1"),
            original.copy(token = "replacement-credential"),
            original.copy(hostId = "host-2"),
            original.copy(workspaceId = "ws-2"),
        ).forEach { replacement ->
            val replacementKey = connectionRouteBinding(listOf(replacement), replacement.id, "detail-spec-1")!!.viewModelKey
            assertNotEquals(originalKey, replacementKey)
        }
    }

    @Test fun unchanged_connection_snapshot_keeps_its_route_view_model_store() {
        val holder = ConnectionRouteViewModelStore()
        val first = holder.ownerFor("detail-spec-1-original")
        val retained = ClearingViewModel()
        first.viewModelStore.put("detail", retained)

        val recomposed = holder.ownerFor("detail-spec-1-original")

        assertSame(first, recomposed)
        assertFalse(retained.cleared)
    }

    @Test fun replaced_connection_snapshot_clears_its_prior_route_view_model_store() {
        val holder = ConnectionRouteViewModelStore()
        val original = holder.ownerFor("detail-spec-1-original")
        val stale = ClearingViewModel()
        original.viewModelStore.put("detail", stale)

        val replacement = holder.ownerFor("detail-spec-1-replacement")

        assertTrue(stale.cleared)
        assertTrue(replacement !== original)
    }

    @Test fun removed_connection_clears_the_current_route_view_model_store() {
        val holder = ConnectionRouteViewModelStore()
        val current = ClearingViewModel()
        holder.ownerFor("detail-spec-1").viewModelStore.put("detail", current)

        holder.clearCurrent()

        assertTrue(current.cleared)
    }

    @Test fun route_entry_clear_clears_the_current_snapshot_view_model_store() {
        val routeEntryStore = ViewModelStore()
        val holder = ConnectionRouteViewModelStore()
        val current = ClearingViewModel()
        holder.ownerFor("detail-spec-1").viewModelStore.put("detail", current)
        routeEntryStore.put("route", holder)

        routeEntryStore.clear()

        assertTrue(current.cleared)
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

    private class ClearingViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

}
