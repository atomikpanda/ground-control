package com.atomikpanda.groundcontrol.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.HomeFeed
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A chip in the workspace rail. connectionId == null is the pinned "All" chip. */
data class WorkspaceChip(val connectionId: String?, val label: String, val count: Int)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object EmptyConfig : HomeUiState
    data class Content(
        val rail: List<WorkspaceChip>,
        val selectedConnectionId: String?,   // null == All
        val items: List<NeedsYouItem>,        // already filtered by selection
        val errors: List<WorkspaceError>,
    ) : HomeUiState
}

class HomeViewModel(
    private val repo: HomeFeedRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var feed: HomeFeed = HomeFeed(emptyList(), emptyList())
    private var selected: String? = null

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = HomeUiState.EmptyConfig; return null }
        _state.value = HomeUiState.Loading
        return (testScope ?: viewModelScope).launch {
            feed = repo.load(connections)
            render(connections)
        }
    }

    /** Select a workspace chip to scope the queue; null == All. */
    fun select(connectionId: String?) {
        selected = connectionId
        val connections = connectionsProvider()
        if (connections.isNotEmpty()) render(connections)
    }

    private fun render(connections: List<WorkspaceConnection>) {
        val counts = feed.items.groupingBy { it.connectionId }.eachCount()
        val chips = buildList {
            add(WorkspaceChip(null, "All", feed.items.size))
            connections
                .sortedWith(
                    compareByDescending<WorkspaceConnection> { counts[it.id] ?: 0 }
                        .thenBy { it.displayName().lowercase() }
                )
                .forEach { add(WorkspaceChip(it.id, it.displayName(), counts[it.id] ?: 0)) }
        }
        val visible = if (selected == null) feed.items else feed.items.filter { it.connectionId == selected }
        _state.value = HomeUiState.Content(chips, selected, visible, feed.errors)
    }
}
