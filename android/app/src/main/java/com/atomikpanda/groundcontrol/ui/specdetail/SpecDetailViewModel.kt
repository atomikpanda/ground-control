package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.Summary
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import com.atomikpanda.groundcontrol.ui.RouteConnectionSnapshot
import com.atomikpanda.groundcontrol.data.dto.DispatchResult
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.SpecReview
import com.atomikpanda.groundcontrol.data.parseApproveBlockers
import com.atomikpanda.groundcontrol.data.summaryOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ErrorKind { NETWORK, AUTH, NOT_FOUND }

/** Which action is in flight, so the UI can show a targeted spinner. */
sealed interface ActionRef {
    data class Verdict(val criterionId: String) : ActionRef
    data class Answer(val questionId: String) : ActionRef
    data object Ask : ActionRef
    data object Approve : ActionRef
    data object RequestChanges : ActionRef
    data object Dispatch : ActionRef
}

data class SpecDetail(
    val id: String,
    val title: String,
    val status: String,
    val bodyMarkdown: String,
    val nonGoals: List<String>,
    val risks: List<String>,
    val affectedRepos: List<String>,
    val taskSlug: String?,
    val criteria: List<ReviewCriterion>,
    val questions: List<ReviewQuestion>,
    val taskPhase: String? = null,
    val taskLastActivityAt: String? = null,
    val taskFinished: Boolean = false,
) {
    val summary: Summary get() = summaryOf(criteria, questions)

    /** Prominent lead atop the screen when this review-phase spec has unanswered questions (ac1);
     *  null when there are none (ac5). */
    val unansweredLead: String? get() = unansweredQuestionsLead(status, summary)

    /** Approve-control guidance when unanswered questions are the sole approval blocker (ac2);
     *  null otherwise. */
    val approveGuidance: String? get() = soleBlockerApproveLabel(status, summary)
}

data class DispatchInfo(val taskSlug: String, val spawned: Boolean, val handoff: String)

sealed interface SpecDetailUiState {
    data object Loading : SpecDetailUiState
    data class Unavailable(val message: String) : SpecDetailUiState
    data class Error(val kind: ErrorKind, val message: String) : SpecDetailUiState
    data class Content(
        val detail: SpecDetail,
        val inFlight: ActionRef? = null,
        val banner: String? = null,            // transient note (e.g. "spec changed")
        val blockers: List<String>? = null,    // approve-gate 409 → blocker sheet
        val dispatchResult: DispatchInfo? = null,
    ) : SpecDetailUiState
}

private fun SpecRecord.toDetail() = SpecDetail(
    id = id, title = title, status = status, bodyMarkdown = body,
    nonGoals = nonGoals, risks = risks, affectedRepos = affectedRepos, taskSlug = taskSlug,
    criteria = acceptanceCriteria, questions = openQuestions,
)

class SpecDetailViewModel(
    private val repo: SpecDetailRepository,
    connectionId: String,
    private val specId: String,
    connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<SpecDetailUiState>(SpecDetailUiState.Loading)
    val state: StateFlow<SpecDetailUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope
    private var loadJob: Job? = null
    private val routeConnection = ReactiveRouteConnection(connectionId, connectionState, viewModelScope) { source, snapshot ->
        loadJob?.cancel()
        if (snapshot == null) {
            _state.value = SpecDetailUiState.Unavailable(if (source is ConnectionState.Error) "Connections unavailable." else "Connection removed.")
        } else {
            loadJob = load(snapshot)
        }
    }
    suspend fun loadEvidence(ref: String): ByteArray {
        val snapshot = routeConnection.current() ?: error("Connection unavailable")
        return repo.loadEvidence(snapshot.connection, specId, ref)
    }
    private fun content() = _state.value as? SpecDetailUiState.Content

    // Unsent free-text drafts kept OUTSIDE the load lifecycle so they survive a leave+return (ac9).
    // Answer drafts are keyed by question id so switching questions never cross-contaminates.
    private val _answerDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val answerDrafts: StateFlow<Map<String, String>> = _answerDrafts.asStateFlow()

    private val _askDraft = MutableStateFlow("")
    val askDraft: StateFlow<String> = _askDraft.asStateFlow()

    fun setAnswerDraft(questionId: String, text: String) {
        _answerDrafts.value = _answerDrafts.value + (questionId to text)
    }

    private fun clearAnswerDraft(questionId: String) {
        _answerDrafts.value = _answerDrafts.value - questionId
    }

    fun setAskDraft(text: String) { _askDraft.value = text }

    // Request-changes reason draft, preserved on a failed submit (mirrors the answer/ask drafts) so a
    // network failure never silently drops the typed reason — the sheet reopens with it intact.
    private val _requestChangesDraft = MutableStateFlow("")
    val requestChangesDraft: StateFlow<String> = _requestChangesDraft.asStateFlow()
    fun setRequestChangesDraft(text: String) { _requestChangesDraft.value = text }

    fun load(): Job = routeConnection.current()?.let(::load) ?: scope().launch { }

    private fun load(snapshot: RouteConnectionSnapshot): Job = scope().launch {
        routeConnection.publishIfCurrent(snapshot) { _state.value = SpecDetailUiState.Loading }
        val result = runCatching { repo.load(snapshot.connection, specId) }
        routeConnection.publishIfCurrent(snapshot) {
            _state.value = result.fold(
                onSuccess = { SpecDetailUiState.Content(it.toDetail()) },
                onFailure = { SpecDetailUiState.Error(it.toKind(), it.message ?: "error") },
            )
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }

    private fun isSpecInFlight(status: String): Boolean = status == "dispatched"

    /**
     * Poll the task behind a dispatched spec so the compact stepper + chip advance live.
     * Runs only while the spec is in-flight (`dispatched`) and stops at terminal
     * (`implemented`/`archived`) or when the task can't be resolved. Mirrors
     * ConsoleViewModel.startPolling. Cancel via the returned Job (bound to the screen lifecycle).
     */
    fun startActivityPolling(intervalMs: Long = 4000): Job = scope().launch {
        while (isActive) {
            val c = content() ?: break
            if (!isSpecInFlight(c.detail.status)) break
            delay(intervalMs)
            if (!refreshActivityOnce()) break
        }
    }

    /** One activity tick: re-read the spec (authoritative status) + its task (phase + activity).
     *  Returns true to keep polling. Transient fetch failures keep polling; a missing task slug
     *  or missing content stops it. */
    private suspend fun refreshActivityOnce(): Boolean {
        val snapshot = routeConnection.current() ?: return false
        val slug = content()?.detail?.taskSlug ?: return false
        val spec = runCatching { repo.load(snapshot.connection, specId) }.getOrNull() ?: return true
        val task = runCatching { repo.loadTask(snapshot.connection, spec.taskSlug ?: slug) }.getOrNull()
        var keepPolling = false
        val published = routeConnection.publishIfCurrent(snapshot) {
            val c = content() ?: return@publishIfCurrent
            _state.value = c.copy(
                detail = c.detail.copy(
                    status = spec.status,
                    taskSlug = spec.taskSlug ?: c.detail.taskSlug,
                    taskPhase = task?.phase ?: c.detail.taskPhase,
                    taskLastActivityAt = task?.lastActivityAt ?: c.detail.taskLastActivityAt,
                    taskFinished = if (task != null) task.finishedAt != null else c.detail.taskFinished,
                ),
            )
            keepPolling = isSpecInFlight(spec.status)
        }
        return published && keepPolling
    }

    /** Apply a write's returned review payload over the current detail (body unchanged). */
    private fun applyReview(rev: SpecReview) {
        val c = content() ?: return
        _state.value = c.copy(
            detail = c.detail.copy(status = rev.status, criteria = rev.acceptanceCriteria, questions = rev.openQuestions),
            inFlight = null,
        )
    }

    private suspend fun refetchWithBanner(snapshot: RouteConnectionSnapshot, banner: String) {
        load(snapshot).join()
        routeConnection.publishIfCurrent(snapshot) {
            content()?.let { _state.value = it.copy(banner = banner) }
        }
    }

    /** Run a write that returns a review; on success patch state, else surface a banner. */
    private fun write(
        ref: ActionRef,
        onSuccess: () -> Unit = {},
        block: suspend (WorkspaceConnection) -> SpecReview,
    ): Job? {
        val snapshot = routeConnection.current() ?: return null
        val c = content() ?: return null
        if (!routeConnection.publishIfCurrent(snapshot) {
                _state.value = c.copy(inFlight = ref, banner = null, blockers = null)
            }) return null
        return scope().launch {
            val result = runCatching { block(snapshot.connection) }
            result.getOrNull()?.let { review ->
                routeConnection.publishIfCurrent(snapshot) {
                    applyReview(review)
                    onSuccess()
                }
                return@launch
            }
            val error = result.exceptionOrNull() ?: return@launch
            var refetch = false
            val published = routeConnection.publishIfCurrent(snapshot) {
                val current = content() ?: return@publishIfCurrent
                when (error) {
                    is ApiConflictException ->
                        if (error.detail.contains("cannot approve")) {
                            _state.value = current.copy(inFlight = null, blockers = parseApproveBlockers(error.detail))
                        } else {
                            _state.value = current.copy(inFlight = null)
                            refetch = true
                        }
                    is AuthException -> _state.value = SpecDetailUiState.Error(ErrorKind.AUTH, error.message ?: "unauthorized")
                    is NotFoundException -> _state.value = SpecDetailUiState.Error(ErrorKind.NOT_FOUND, error.message ?: "gone")
                    else -> _state.value = current.copy(inFlight = null, banner = "Couldn't reach workspace — retry.")
                }
            }
            if (published && refetch) refetchWithBanner(snapshot, "Spec changed since you opened it.")
        }
    }

    fun setVerdict(criterionId: String, verdict: String): Job? =
        write(ActionRef.Verdict(criterionId)) { conn -> repo.setVerdict(conn, specId, criterionId, verdict) }

    fun answer(questionId: String, answer: String): Job? =
        write(ActionRef.Answer(questionId), onSuccess = { clearAnswerDraft(questionId) }) { conn ->
            repo.answer(conn, specId, questionId, answer)
        }

    fun ask(text: String): Job? =
        write(ActionRef.Ask, onSuccess = { _askDraft.value = "" }) { conn -> repo.ask(conn, specId, text) }

    fun approve(bypass: Boolean): Job? = write(ActionRef.Approve) { conn -> repo.approve(conn, specId, bypass) }

    fun requestChanges(reason: String): Job? =
        write(ActionRef.RequestChanges, onSuccess = { _requestChangesDraft.value = "" }) { conn ->
            repo.requestChanges(conn, specId, reason)
        }

    fun dispatch(): Job? {
        val snapshot = routeConnection.current() ?: return null
        val c = content() ?: return null
        if (!routeConnection.publishIfCurrent(snapshot) {
                _state.value = c.copy(inFlight = ActionRef.Dispatch, banner = null, blockers = null)
            }) return null
        return scope().launch {
            val result = runCatching { repo.dispatch(snapshot.connection, specId) }
            result.getOrNull()?.let { dispatch ->
                routeConnection.publishIfCurrent(snapshot) { applyDispatch(dispatch) }
                return@launch
            }
            val error = result.exceptionOrNull() ?: return@launch
            var refetch = false
            val published = routeConnection.publishIfCurrent(snapshot) {
                val current = content() ?: return@publishIfCurrent
                when (error) {
                    is ApiConflictException -> {
                        if (
                            error.detail.contains("auto-spawn", ignoreCase = true) ||
                            error.detail.contains("worktree", ignoreCase = true)
                        ) {
                            _state.value = current.copy(
                                inFlight = null,
                                banner = "Auto-spawn unavailable on this host. Spawn/bind the task from a terminal, then dispatch.",
                            )
                        } else {
                            _state.value = current.copy(inFlight = null)
                            refetch = true
                        }
                    }
                    is AuthException -> _state.value = SpecDetailUiState.Error(ErrorKind.AUTH, error.message ?: "unauthorized")
                    is NotFoundException -> _state.value = SpecDetailUiState.Error(ErrorKind.NOT_FOUND, error.message ?: "gone")
                    else -> _state.value = current.copy(inFlight = null, banner = "Couldn't reach workspace — retry.")
                }
            }
            if (published && refetch) refetchWithBanner(snapshot, "Spec changed since you opened it.")
        }
    }

    private fun applyDispatch(dr: DispatchResult) {
        val c = content() ?: return
        _state.value = c.copy(
            detail = c.detail.copy(status = dr.spec.status, taskSlug = dr.taskSlug),
            inFlight = null,
            dispatchResult = DispatchInfo(dr.taskSlug, dr.spawned, dr.handoff),
        )
    }

    fun dismissBlockers() { content()?.let { _state.value = it.copy(blockers = null) } }
    fun dismissDispatchResult() { content()?.let { _state.value = it.copy(dispatchResult = null) } }
    fun dismissBanner() { content()?.let { _state.value = it.copy(banner = null) } }
}
