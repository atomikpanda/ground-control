package com.atomikpanda.groundcontrol.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        /** Transient note about the assumptions section only (a degraded fetch, or a
         *  post-approve refresh) — distinct from `banner`, which covers the whole task. */
        val assumptionsNotice: String? = null,
    ) : TaskDetailUiState {
        /** True once the assumptions set is known to have gone stale (`fresh == false`): the
         *  plan/assumptions changed since this check, so the gate will reject approvals until a
         *  re-check runs. Unknown (assumptions == null) is treated as not-stale. */
        val isAssumptionsStale: Boolean get() = assumptions?.fresh == false

        /** Approve controls should only be offered while the assumptions set is fresh. */
        val canApproveAssumptions: Boolean get() = !isAssumptionsStale
    }
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

    /** Task + journal are the core content: fetched in parallel, fatal (Error state) on
     *  failure. Assumptions load in parallel alongside them but in their own runCatching —
     *  a failure there degrades to `assumptions = null` with an inline note, never blanks the
     *  whole screen. */
    fun load(): Job {
        _state.value = TaskDetailUiState.Loading
        return scope().launch {
            coroutineScope {
                // Each async wraps its own runCatching: an exception thrown directly inside an
                // async body would fail the whole coroutineScope (cancelling the sibling) before
                // either result could be inspected, defeating the "assumptions degrade, core is
                // fatal" split below.
                val coreDeferred = async { runCatching { repo.getTask(conn, slug) to repo.getJournal(conn, slug) } }
                val assumptionsDeferred = async { runCatching { repo.getPlanAssumptions(conn, slug) } }

                val coreResult = coreDeferred.await()
                val assumptionsResult = assumptionsDeferred.await()

                coreResult
                    .onSuccess { (task, journal) ->
                        _state.value = TaskDetailUiState.Content(
                            task, journal,
                            assumptions = assumptionsResult.getOrNull(),
                            assumptionsNotice = assumptionsResult.exceptionOrNull()
                                ?.let { "Couldn't load assumptions — pull to retry." },
                        )
                    }
                    .onFailure { t ->
                        _state.value = TaskDetailUiState.Error(t.toKind(), t.message ?: "error")
                    }
            }
        }
    }

    /** Approve one pending plan-assumption flag; re-renders `Content.assumptions` from the
     *  returned envelope. A 404/409 here means the flag was already handled (approved, or the
     *  check regenerated) since the operator's last fetch — that's routine staleness, not a
     *  fatal error, so it re-fetches assumptions and notes why the row vanished instead of
     *  blanking task+journal. Only a genuine auth failure is fatal. */
    fun approveFlag(axis: String): Job? {
        val c = content() ?: return null
        _state.value = c.copy(inFlight = ActionRef.ApproveFlag(axis), banner = null, assumptionsNotice = null)
        return scope().launch {
            runCatching { repo.approvePlanFlag(conn, slug, axis, null) }
                .onSuccess { envelope ->
                    val c2 = content() ?: return@onSuccess
                    _state.value = c2.copy(assumptions = envelope, inFlight = null)
                }
                .onFailure { t ->
                    val c2 = content() ?: return@onFailure
                    when (t) {
                        is AuthException -> _state.value = TaskDetailUiState.Error(ErrorKind.AUTH, t.message ?: "unauthorized")
                        else -> {
                            _state.value = c2.copy(inFlight = null)
                            refetchAssumptionsWithNotice("That assumption was already handled — refreshed.")
                        }
                    }
                }
        }
    }

    /** Re-fetch the assumptions envelope and stamp a transient notice explaining the refresh,
     *  leaving task+journal untouched. Used when an approve write turns out to be stale
     *  (404/409/other API failure) rather than a genuine fatal error. */
    private suspend fun refetchAssumptionsWithNotice(notice: String) {
        val fresh = runCatching { repo.getPlanAssumptions(conn, slug) }.getOrNull()
        val c2 = content() ?: return
        _state.value = c2.copy(assumptions = fresh ?: c2.assumptions, assumptionsNotice = notice)
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }
}
