package com.atomikpanda.groundcontrol.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStatePublicationFence
import com.atomikpanda.groundcontrol.data.HomeFeed
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.applyHostLadder
import com.atomikpanda.groundcontrol.data.dedupeHostErrors
import com.atomikpanda.groundcontrol.data.emitAtStaleDeadlines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A chip in the workspace rail. connectionId == null is the pinned "All" chip. */
data class WorkspaceChip(val connectionId: String?, val label: String, val count: Int)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object EmptyConfig : HomeUiState
    data class ConnectionsUnavailable(val cause: Throwable) : HomeUiState
    data class Content(
        val rail: List<WorkspaceChip>,
        val selectedConnectionId: String?,
        val items: List<NeedsYouItem>,
        val notes: List<NewMessageNote>,
        val errors: List<WorkspaceError>,
    ) : HomeUiState
}

class HomeViewModel(
    private val repo: HomeFeedRepository,
    private val connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
    private val hosts: Flow<List<HostConnection>>? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var feed: HomeFeed = HomeFeed(emptyList(), emptyList(), emptyList())
    private var selected: String? = null
    private var lastConnections: List<WorkspaceConnection> = emptyList()
    private var lastHosts: List<HostConnection> = emptyList()
    private var refreshJob: Job? = null
    private var refreshConnections: List<WorkspaceConnection>? = null
    private var refreshGeneration = 0L

    init {
        scope().launch {
            connectionState.collectLatest { connections ->
                val ready = connections as? ConnectionState.Ready
                if (ready != null && refreshConnections == ready.connections && refreshJob?.isActive == true) {
                    refreshJob?.join()
                    return@collectLatest
                }
                refreshJob?.cancelAndJoin()
                refreshConnections = ready?.connections
                when (connections) {
                    ConnectionState.Loading -> _state.value = HomeUiState.Loading
                    is ConnectionState.Error -> _state.value = HomeUiState.ConnectionsUnavailable(connections.cause)
                    is ConnectionState.Ready -> {
                        refreshJob = startReload(connections.connections)
                        refreshJob?.join()
                    }
                }
            }
        }
        hosts?.let { source ->
            scope().launch {
                source.emitAtStaleDeadlines(nowMillis).collect { current ->
                    lastHosts = current
                    if (_state.value is HomeUiState.Content) render(lastConnections)
                }
            }
        }
    }

    private fun scope(): CoroutineScope = testScope ?: viewModelScope

    fun refresh(): Job? = when (val connections = connectionState.value) {
        is ConnectionState.Ready -> startReload(connections.connections)
        ConnectionState.Loading -> { _state.value = HomeUiState.Loading; null }
        is ConnectionState.Error -> { _state.value = HomeUiState.ConnectionsUnavailable(connections.cause); null }
    }

    private fun startReload(connections: List<WorkspaceConnection>): Job? {
        refreshJob?.cancel()
        refreshConnections = connections
        val generation = ++refreshGeneration
        return reload(connections, generation).also { refreshJob = it }
    }

    private fun reload(connections: List<WorkspaceConnection>, generation: Long): Job? {
        if (connections.isEmpty()) {
            selected = null
            _state.value = HomeUiState.EmptyConfig
            return null
        }
        _state.value = HomeUiState.Loading
        return scope().launch {
            val loaded = repo.load(connections)
            val currentHosts = hosts?.first() ?: emptyList()
            synchronized(ConnectionStatePublicationFence.lock) {
                if (
                    refreshGeneration != generation ||
                    (connectionState.value as? ConnectionState.Ready)?.connections != connections
                ) return@launch
                feed = loaded
                lastConnections = connections
                selected = selected?.let { connections.findByConnectionId(it)?.id }
                lastHosts = currentHosts
                render(connections)
            }
        }
    }

    /** Select a workspace chip to scope the queue; null == All. Renders from the snapshot [refresh] loaded. */
    fun select(connectionId: String?) {
        if (_state.value !is HomeUiState.Content) return
        selected = connectionId
        render(lastConnections)
    }

    private fun render(connections: List<WorkspaceConnection>) {
        // Chip counts reflect the action queue (needsYou items) only. Quiet "new message" notes
        // deliberately don't bump the workspace rail — the unread *count* surface is the deferred
        // Messages-tab badge. Notes still render in Home's "New messages" section.
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
        val visibleNotes = if (selected == null) feed.notes else feed.notes.filter { it.connectionId == selected }
        // One dead host is one row, and its wording comes from the same ladder
        // Projects, Queue and Settings render (#471).
        val errors = applyHostLadder(
            feed.errors,
            connections,
            lastHosts,
            nowMillis(),
        )
        _state.value = HomeUiState.Content(chips, selected, visible, visibleNotes, dedupeHostErrors(errors))
    }
}
