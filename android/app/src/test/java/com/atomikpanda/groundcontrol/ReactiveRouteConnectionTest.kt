package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveRouteConnectionTest {
    @Test fun is_current_reads_state_flow_before_collector_observes_replacement() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val route = ReactiveRouteConnection(old.id, source, backgroundScope) { _, _ -> }
        val oldSnapshot = route.current()!!

        source.value = ConnectionState.Ready(listOf(replacement))

        assertFalse(route.isCurrent(oldSnapshot))
    }

    @Test fun publication_fence_rejects_a_snapshot_replaced_before_its_result_is_published() = runTest {
        val old = WorkspaceConnection("workspace", "http://old:47100", "old-token", "Old")
        val replacement = old.copy(baseUrl = "http://new:47100", token = "new-token", workspaceName = "New")
        val source = MutableStateFlow<ConnectionState>(ConnectionState.Ready(listOf(old)))
        val route = ReactiveRouteConnection(old.id, source, backgroundScope) { _, _ -> }
        val oldSnapshot = route.current()!!
        var published = false

        source.value = ConnectionState.Ready(listOf(replacement))

        assertFalse(route.publishIfCurrent(oldSnapshot) { published = true })
        assertFalse(published)
    }
}
