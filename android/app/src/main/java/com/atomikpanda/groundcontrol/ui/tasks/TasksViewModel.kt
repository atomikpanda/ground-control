package com.atomikpanda.groundcontrol.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStatePublicationFence
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

enum class TaskGroup(val label: String) { ACTIVE("Active"), FINISHED("Finished") }

fun taskGroupFor(t: TaskSummary): TaskGroup =
    if (t.finishedAt == null) TaskGroup.ACTIVE else TaskGroup.FINISHED

data class TaskGroupBlock(val group: TaskGroup, val tasks: List<TaskSummary>)

data class TasksSection(
    val workspaceName: String,
    val connectionId: String,
    val groups: Result<List<TaskGroupBlock>>,
)

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data object EmptyConfig : TasksUiState
    data class ConnectionsUnavailable(val cause: Throwable) : TasksUiState
    data class Content(val sections: List<TasksSection>) : TasksUiState
}

class TasksViewModel(
    private val repo: TasksRepository,
    private val connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<TasksUiState>(TasksUiState.Loading)
    val state: StateFlow<TasksUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshConnections: List<WorkspaceConnection>? = null
    private var refreshGeneration = 0L

    init {
        scope().launch {
            connectionState.collectLatest { state ->
                val ready = state as? ConnectionState.Ready
                if (ready != null && refreshConnections == ready.connections && refreshJob?.isActive == true) {
                    refreshJob?.join()
                    return@collectLatest
                }
                refreshJob?.cancelAndJoin()
                refreshConnections = ready?.connections
                when (state) {
                    ConnectionState.Loading -> _state.value = TasksUiState.Loading
                    is ConnectionState.Error -> _state.value = TasksUiState.ConnectionsUnavailable(state.cause)
                    is ConnectionState.Ready -> {
                        refreshJob = startReload(state.connections)
                        refreshJob?.join()
                    }
                }
            }
        }
    }

    private fun scope(): CoroutineScope = testScope ?: viewModelScope

    fun refresh(): Job? = when (val state = connectionState.value) {
        is ConnectionState.Ready -> startReload(state.connections)
        ConnectionState.Loading -> { _state.value = TasksUiState.Loading; null }
        is ConnectionState.Error -> { _state.value = TasksUiState.ConnectionsUnavailable(state.cause); null }
    }

    private fun startReload(connections: List<WorkspaceConnection>): Job? {
        refreshJob?.cancel()
        refreshConnections = connections
        val generation = ++refreshGeneration
        return reload(connections, generation).also { refreshJob = it }
    }

    private fun reload(connections: List<WorkspaceConnection>, generation: Long): Job? {
        if (connections.isEmpty()) {
            _state.value = TasksUiState.EmptyConfig
            return null
        }
        _state.value = TasksUiState.Loading
        return scope().launch {
            val results = repo.listAllTasks(connections)
            synchronized(ConnectionStatePublicationFence.lock) {
                if (
                    refreshGeneration != generation ||
                    (connectionState.value as? ConnectionState.Ready)?.connections != connections
                ) return@launch
                _state.value = TasksUiState.Content(
                    results.map { ws ->
                        TasksSection(
                            workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                            connectionId = ws.connection.id,
                            groups = ws.tasks.map { tasks -> toGroupBlocks(tasks) },
                        )
                    },
                )
            }
        }
    }

    private fun toGroupBlocks(tasks: List<TaskSummary>): List<TaskGroupBlock> {
        val byGroup = tasks.groupBy { taskGroupFor(it) }
        return TaskGroup.entries.mapNotNull { g ->
            byGroup[g]?.takeIf { it.isNotEmpty() }?.let { TaskGroupBlock(g, it) }
        }
    }
}
