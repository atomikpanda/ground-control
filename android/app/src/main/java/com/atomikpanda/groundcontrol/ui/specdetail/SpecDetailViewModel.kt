package com.atomikpanda.groundcontrol.ui.specdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.Summary
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
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
}

data class DispatchInfo(val taskSlug: String, val spawned: Boolean, val handoff: String)

sealed interface SpecDetailUiState {
    data object Loading : SpecDetailUiState
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
    private val conn: WorkspaceConnection,
    private val specId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<SpecDetailUiState>(SpecDetailUiState.Loading)
    val state: StateFlow<SpecDetailUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope
    private fun content() = _state.value as? SpecDetailUiState.Content

    fun load(): Job? {
        _state.value = SpecDetailUiState.Loading
        return scope().launch {
            runCatching { repo.load(conn, specId) }
                .onSuccess { _state.value = SpecDetailUiState.Content(it.toDetail()) }
                .onFailure { _state.value = SpecDetailUiState.Error(it.toKind(), it.message ?: "error") }
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
        val slug = content()?.detail?.taskSlug ?: return false
        val spec = runCatching { repo.load(conn, specId) }.getOrNull() ?: return true
        val task = runCatching { repo.loadTask(conn, spec.taskSlug ?: slug) }.getOrNull()
        val c = content() ?: return false
        _state.value = c.copy(
            detail = c.detail.copy(
                status = spec.status,
                taskSlug = spec.taskSlug ?: c.detail.taskSlug,
                taskPhase = task?.phase ?: c.detail.taskPhase,
                taskLastActivityAt = task?.lastActivityAt ?: c.detail.taskLastActivityAt,
                // Preserve the prior value on a transient task-fetch miss (task == null)
                // rather than silently reverting a recorded "finished" back to false.
                taskFinished = if (task != null) task.finishedAt != null else c.detail.taskFinished,
            ),
        )
        return isSpecInFlight(spec.status)
    }

    /** Apply a write's returned review payload over the current detail (body unchanged). */
    private fun applyReview(rev: SpecReview) {
        val c = content() ?: return
        _state.value = c.copy(
            detail = c.detail.copy(status = rev.status, criteria = rev.acceptanceCriteria, questions = rev.openQuestions),
            inFlight = null,
        )
    }

    private suspend fun refetchWithBanner(banner: String) {
        load()?.join()
        content()?.let { _state.value = it.copy(banner = banner) }
    }

    /** Run a write that returns a review; on success patch state, else surface a banner. */
    private fun write(ref: ActionRef, block: suspend () -> SpecReview): Job? {
        val c = content() ?: return null
        _state.value = c.copy(inFlight = ref, banner = null, blockers = null)
        return scope().launch {
            runCatching { block() }
                .onSuccess { applyReview(it) }
                .onFailure { t ->
                    val c2 = content() ?: return@onFailure
                    when (t) {
                        is ApiConflictException ->
                            if (t.detail.contains("cannot approve"))
                                _state.value = c2.copy(inFlight = null, blockers = parseApproveBlockers(t.detail))
                            else { _state.value = c2.copy(inFlight = null); refetchWithBanner("Spec changed since you opened it.") }
                        is AuthException -> _state.value = SpecDetailUiState.Error(ErrorKind.AUTH, t.message ?: "unauthorized")
                        is NotFoundException -> _state.value = SpecDetailUiState.Error(ErrorKind.NOT_FOUND, t.message ?: "gone")
                        else -> _state.value = c2.copy(inFlight = null, banner = "Couldn't reach workspace — retry.")
                    }
                }
        }
    }

    fun setVerdict(criterionId: String, verdict: String): Job? =
        write(ActionRef.Verdict(criterionId)) { repo.setVerdict(conn, specId, criterionId, verdict) }

    fun answer(questionId: String, answer: String): Job? =
        write(ActionRef.Answer(questionId)) { repo.answer(conn, specId, questionId, answer) }

    fun ask(text: String): Job? = write(ActionRef.Ask) { repo.ask(conn, specId, text) }

    fun approve(bypass: Boolean): Job? = write(ActionRef.Approve) { repo.approve(conn, specId, bypass) }

    fun requestChanges(reason: String): Job? =
        write(ActionRef.RequestChanges) { repo.requestChanges(conn, specId, reason) }

    fun dispatch(): Job? {
        val c = content() ?: return null
        _state.value = c.copy(inFlight = ActionRef.Dispatch, banner = null, blockers = null)
        return scope().launch {
            runCatching { repo.dispatch(conn, specId) }
                .onSuccess { applyDispatch(it) }
                .onFailure { t ->
                    val snapshot = content() ?: return@onFailure
                    when (t) {
                        is ApiConflictException -> {
                            if (t.detail.contains("auto-spawn", ignoreCase = true) || t.detail.contains("worktree", ignoreCase = true)) {
                                // auto-spawn-unavailable: surface actionable message without refetching so status stays as-is
                                _state.value = snapshot.copy(
                                    inFlight = null,
                                    banner = "Auto-spawn unavailable on this host. Spawn/bind the task from a terminal, then dispatch.",
                                )
                            } else {
                                // stale 409 (not an approve gate): refetch and then set banner on the fresh content
                                _state.value = snapshot.copy(inFlight = null)
                                refetchWithBanner("Spec changed since you opened it.")
                            }
                        }
                        is AuthException -> _state.value = SpecDetailUiState.Error(ErrorKind.AUTH, t.message ?: "unauthorized")
                        is NotFoundException -> _state.value = SpecDetailUiState.Error(ErrorKind.NOT_FOUND, t.message ?: "gone")
                        else -> _state.value = snapshot.copy(inFlight = null, banner = "Couldn't reach workspace — retry.")
                    }
                }
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
