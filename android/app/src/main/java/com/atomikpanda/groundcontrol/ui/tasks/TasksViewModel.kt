package com.atomikpanda.groundcontrol.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Content(val sections: List<TasksSection>) : TasksUiState
}

class TasksViewModel(
    private val repo: TasksRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<TasksUiState>(TasksUiState.Loading)
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = TasksUiState.EmptyConfig; return null }
        _state.value = TasksUiState.Loading
        return (testScope ?: viewModelScope).launch {
            val results = repo.listAllTasks(connections)
            _state.value = TasksUiState.Content(
                results.map { ws ->
                    TasksSection(
                        workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                        connectionId = ws.connection.id,
                        groups = ws.tasks.map { tasks -> toGroupBlocks(tasks) },
                    )
                }
            )
        }
    }

    private fun toGroupBlocks(tasks: List<TaskSummary>): List<TaskGroupBlock> {
        val byGroup = tasks.groupBy { taskGroupFor(it) }
        return TaskGroup.entries.mapNotNull { g ->
            byGroup[g]?.takeIf { it.isNotEmpty() }?.let { TaskGroupBlock(g, it) }
        }
    }
}
