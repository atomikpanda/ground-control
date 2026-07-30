package com.atomikpanda.groundcontrol.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.PlanAssumptionsEnvelope
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which action is in flight, so the UI can show a targeted spinner. */
sealed interface ActionRef {
    data class ApproveFlag(val axis: String) : ActionRef
}

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Error(val kind: ErrorKind, val message: String) : TaskDetailUiState
    data class Content(
        val task: TaskSummary,
        val journal: List<JournalEntry>,
        val assumptions: PlanAssumptionsEnvelope? = null,
        val inFlight: ActionRef? = null,
        val banner: String? = null,
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
    private fun content() = _state.value as? TaskDetailUiState.Content

    fun load(): Job {
        _state.value = TaskDetailUiState.Loading
        return scope().launch {
            runCatching {
                val task = repo.getTask(conn, slug)
                val journal = repo.getJournal(conn, slug)
                val assumptions = repo.getPlanAssumptions(conn, slug)
                Triple(task, journal, assumptions)
            }
                .onSuccess { (task, journal, assumptions) ->
                    _state.value = TaskDetailUiState.Content(task, journal, assumptions)
                }
                .onFailure { t ->
                    _state.value = TaskDetailUiState.Error(t.toKind(), t.message ?: "error")
                }
        }
    }

    /** Approve one pending plan-assumption flag; re-renders `Content.assumptions` from the
     *  returned envelope. Reuses the same conflict/not-found handling as SpecDetailViewModel's writes. */
    fun approveFlag(axis: String): Job? {
        val c = content() ?: return null
        _state.value = c.copy(inFlight = ActionRef.ApproveFlag(axis), banner = null)
        return scope().launch {
            runCatching { repo.approvePlanFlag(conn, slug, axis, null) }
                .onSuccess { envelope ->
                    val c2 = content() ?: return@onSuccess
                    _state.value = c2.copy(assumptions = envelope, inFlight = null)
                }
                .onFailure { t ->
                    val c2 = content() ?: return@onFailure
                    when (t) {
                        is ApiConflictException ->
                            _state.value = c2.copy(inFlight = null, banner = "Assumptions changed since you opened this task.")
                        is AuthException -> _state.value = TaskDetailUiState.Error(ErrorKind.AUTH, t.message ?: "unauthorized")
                        is NotFoundException -> _state.value = TaskDetailUiState.Error(ErrorKind.NOT_FOUND, t.message ?: "gone")
                        else -> _state.value = c2.copy(inFlight = null, banner = "Couldn't reach workspace — retry.")
                    }
                }
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }
}
