package com.atomikpanda.groundcontrol.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import com.atomikpanda.groundcontrol.ui.RouteConnectionSnapshot
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
    data class Unavailable(val message: String) : TaskDetailUiState
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
    connectionId: String,
    private val slug: String,
    connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<TaskDetailUiState>(TaskDetailUiState.Loading)
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    /** The currently in-flight approve job, if any — load() awaits it before fetching so a
     *  concurrent refresh can't clobber the approval's own state update (see load() doc). */
    private var approveJob: Job? = null

    private fun scope() = testScope ?: viewModelScope
    private var loadJob: Job? = null
    private val routeConnection = ReactiveRouteConnection(connectionId, connectionState, viewModelScope) { source, snapshot ->
        loadJob?.cancel()
        approveJob?.cancel()
        if (snapshot == null) {
            _state.value = TaskDetailUiState.Unavailable(if (source is ConnectionState.Error) "Connections unavailable." else "Connection removed.")
        } else {
            loadJob = load(snapshot)
        }
    }
    private fun content() = _state.value as? TaskDetailUiState.Content

    /** Task + journal are the core content: fetched in parallel, fatal (Error state) on
     *  failure. Assumptions load in parallel alongside them but in their own runCatching —
     *  a failure there degrades to `assumptions = null` with an inline note, never blanks the
     *  whole screen. */
    fun load(): Job = routeConnection.current()?.let(::load) ?: scope().launch { }

    private fun load(snapshot: RouteConnectionSnapshot): Job = scope().launch {
        if (content()?.inFlight is ActionRef.ApproveFlag) approveJob?.join()
        routeConnection.publishIfCurrent(snapshot) { _state.value = TaskDetailUiState.Loading }
        coroutineScope {
            val coreDeferred = async {
                runCatching {
                    repo.getTask(snapshot.connection, slug) to repo.getJournal(snapshot.connection, slug)
                }
            }
            val assumptionsDeferred = async {
                runCatching { repo.getPlanAssumptions(snapshot.connection, slug) }
            }
            val coreResult = coreDeferred.await()
            val assumptionsResult = assumptionsDeferred.await()
            routeConnection.publishIfCurrent(snapshot) {
                _state.value = coreResult.fold(
                    onSuccess = { (task, journal) ->
                        TaskDetailUiState.Content(
                            task,
                            journal,
                            assumptions = assumptionsResult.getOrNull(),
                            assumptionsNotice = assumptionsResult.exceptionOrNull()
                                ?.let { "Couldn't load assumptions — pull to retry." },
                        )
                    },
                    onFailure = { TaskDetailUiState.Error(it.toKind(), it.message ?: "error") },
                )
            }
        }
    }

    /** Approve one pending plan-assumption flag; re-renders `Content.assumptions` from the
     *  returned envelope. A 404/409 here means the flag was already handled (approved, or the
     *  check regenerated) since the operator's last fetch — that's routine staleness, not a
     *  fatal error, so it re-fetches assumptions and notes why the row vanished instead of
     *  blanking task+journal. A genuine auth failure is fatal. Any other failure (timeout,
     *  5xx, decode error) means the approval never happened — it must NOT be presented as
     *  success/refreshed, and the pending flag must stay visible so the operator can retry. */
    fun approveFlag(axis: String): Job? {
        val snapshot = routeConnection.current() ?: return null
        val c = content() ?: return null
        if (c.inFlight is ActionRef.ApproveFlag) return null
        if (!routeConnection.publishIfCurrent(snapshot) {
                _state.value = c.copy(inFlight = ActionRef.ApproveFlag(axis), banner = null, assumptionsNotice = null)
            }) return null
        val job = scope().launch {
            val result = runCatching { repo.approvePlanFlag(snapshot.connection, slug, axis, null) }
            result.getOrNull()?.let { envelope ->
                routeConnection.publishIfCurrent(snapshot) {
                    val current = content() ?: return@publishIfCurrent
                    _state.value = current.copy(assumptions = envelope, inFlight = null)
                }
                return@launch
            }
            when (val error = result.exceptionOrNull() ?: return@launch) {
                is NotFoundException, is ApiConflictException -> {
                    if (routeConnection.isCurrent(snapshot)) refetchAssumptionsWithNotice(snapshot)
                }
                else -> routeConnection.publishIfCurrent(snapshot) {
                    val current = content() ?: return@publishIfCurrent
                    _state.value = if (error is AuthException) {
                        TaskDetailUiState.Error(ErrorKind.AUTH, error.message ?: "unauthorized")
                    } else {
                        current.copy(inFlight = null, assumptionsNotice = "Approval failed — tap Approve to retry.")
                    }
                }
            }
        }
        approveJob = job
        return job
    }

    /** Re-fetch the assumptions envelope and stamp a transient notice reflecting the refetch's
     *  own outcome, leaving task+journal untouched. Used when an approve write turns out to be
     *  stale (404/409) rather than a genuine fatal error. The refetch can itself fail (network,
     *  5xx) — that must never be presented as a successful refresh: the notice must say the
     *  refresh failed, and the prior assumptions/pending state is left visibly intact. */
    private suspend fun refetchAssumptionsWithNotice(snapshot: RouteConnectionSnapshot) {
        val result = runCatching { repo.getPlanAssumptions(snapshot.connection, slug) }
        routeConnection.publishIfCurrent(snapshot) {
            val current = content() ?: return@publishIfCurrent
            _state.value = result.fold(
                onSuccess = {
                    current.copy(
                        assumptions = it,
                        inFlight = null,
                        assumptionsNotice = "That assumption was already handled — refreshed.",
                    )
                },
                onFailure = {
                    current.copy(
                        inFlight = null,
                        assumptionsNotice = "That assumption changed, but refreshing failed — pull to refresh.",
                    )
                },
            )
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }
}
