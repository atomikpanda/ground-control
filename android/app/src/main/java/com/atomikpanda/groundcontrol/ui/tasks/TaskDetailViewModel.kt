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

    /** The currently in-flight approve job, if any — load() awaits it before fetching so a
     *  concurrent refresh can't clobber the approval's own state update (see load() doc). */
    private var approveJob: Job? = null

    private fun scope() = testScope ?: viewModelScope
    private fun content() = _state.value as? TaskDetailUiState.Content

    /** Task + journal are the core content: fetched in parallel, fatal (Error state) on
     *  failure. Assumptions load in parallel alongside them but in their own runCatching —
     *  a failure there degrades to `assumptions = null` with an inline note, never blanks the
     *  whole screen. */
    fun load(): Job {
        return scope().launch {
            // An approve may be in flight: flipping to Loading now would make approveFlag's
            // own completion find no Content to update (content() returns null), silently
            // dropping its result. Defer this refresh until the approve settles — the
            // approval result lands first, then this fetch runs and picks up the fresh
            // (already-approved) state, so the operator's refresh is still honored.
            //
            // The defer decision is gated on `inFlight`, not on `approveJob` directly:
            // `inFlight` is set synchronously by approveFlag() before it returns, so reading
            // it here (inside this coroutine's body, not captured before launch) reliably
            // detects an in-flight approve regardless of which dispatcher runs this body.
            // Capturing `approveJob` itself before/outside the coroutine would be a
            // check-then-act on two fields approveFlag sets in sequence (inFlight, then
            // approveJob) — safe only because viewModelScope happens to run inline to the
            // first suspension; gating on the synchronously-set `inFlight` instead removes
            // that assumption entirely.
            if (content()?.inFlight is ActionRef.ApproveFlag) {
                approveJob?.join()
            }
            _state.value = TaskDetailUiState.Loading
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
     *  blanking task+journal. A genuine auth failure is fatal. Any other failure (timeout,
     *  5xx, decode error) means the approval never happened — it must NOT be presented as
     *  success/refreshed, and the pending flag must stay visible so the operator can retry. */
    fun approveFlag(axis: String): Job? {
        val c = content() ?: return null
        // Only one approve may be in flight at a time: approveJob is a single reference (load()
        // awaits it before refreshing), so a second concurrent approve would silently take over
        // that slot and let the first approve's in-flight result get clobbered by a refresh.
        if (c.inFlight is ActionRef.ApproveFlag) return null
        _state.value = c.copy(inFlight = ActionRef.ApproveFlag(axis), banner = null, assumptionsNotice = null)
        val job = scope().launch {
            runCatching { repo.approvePlanFlag(conn, slug, axis, null) }
                .onSuccess { envelope ->
                    val c2 = content() ?: return@onSuccess
                    _state.value = c2.copy(assumptions = envelope, inFlight = null)
                }
                .onFailure { t ->
                    val c2 = content() ?: return@onFailure
                    when (t) {
                        is AuthException -> _state.value = TaskDetailUiState.Error(ErrorKind.AUTH, t.message ?: "unauthorized")
                        is NotFoundException, is ApiConflictException -> {
                            // Do NOT clear inFlight here: refetchAssumptionsWithNotice() below
                            // suspends, and releasing the lock before it completes would let a
                            // second approve start mid-refetch and clobber this one's outcome.
                            // The lock is released exactly once, in that function's own
                            // terminal state update.
                            refetchAssumptionsWithNotice()
                        }
                        else -> _state.value = c2.copy(
                            inFlight = null,
                            assumptionsNotice = "Approval failed — tap Approve to retry.",
                        )
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
    private suspend fun refetchAssumptionsWithNotice() {
        val result = runCatching { repo.getPlanAssumptions(conn, slug) }
        val c2 = content() ?: return
        // Terminal state for the reconcile: this is where the approve serialization lock
        // (inFlight) is released — exactly once, after the refetch itself has settled.
        _state.value = result.fold(
            onSuccess = { fresh -> c2.copy(assumptions = fresh, inFlight = null, assumptionsNotice = "That assumption was already handled — refreshed.") },
            onFailure = { c2.copy(inFlight = null, assumptionsNotice = "That assumption changed, but refreshing failed — pull to refresh.") },
        )
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }
}
