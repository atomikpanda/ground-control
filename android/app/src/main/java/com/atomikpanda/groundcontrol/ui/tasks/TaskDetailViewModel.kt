package com.atomikpanda.groundcontrol.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Error(val kind: ErrorKind, val message: String) : TaskDetailUiState
    data class Content(
        val task: TaskSummary,
        val journal: List<JournalEntry>,
    ) : TaskDetailUiState
}

class TaskDetailViewModel(
    private val repo: TasksRepository,
    private val conn: WorkspaceConnection,
    private val slug: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<TaskDetailUiState>(TaskDetailUiState.Loading)
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope

    fun load(): Job {
        _state.value = TaskDetailUiState.Loading
        return scope().launch {
            runCatching {
                val task = repo.getTask(conn, slug)
                val journal = repo.getJournal(conn, slug)
                Pair(task, journal)
            }
                .onSuccess { (task, journal) ->
                    _state.value = TaskDetailUiState.Content(task, journal)
                }
                .onFailure { t ->
                    _state.value = TaskDetailUiState.Error(t.toKind(), t.message ?: "error")
                }
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }
}
