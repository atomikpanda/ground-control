package com.atomikpanda.groundcontrol.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import com.atomikpanda.groundcontrol.ui.RouteConnectionSnapshot
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class PrRow(val taskSlug: String, val repo: String, val url: String, val testStatus: String?)
data class ReviewContent(
    val item: WorkItemSummary,
    val prs: List<PrRow>,
    val threadId: String?,
    val criteria: List<ReviewCriterion> = emptyList(),
    val prUrls: List<String> = emptyList(),
)

sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data class Unavailable(val message: String) : ReviewUiState
    data class Content(val c: ReviewContent) : ReviewUiState
    data class Failed(val reason: String) : ReviewUiState
}

/** Review cockpit for a single work item: fans out GET /items/{id} into its tasks,
 *  aggregates each task's `pr_urls`/`test_results` into per-repo `PrRow`s, and
 *  posts `requestChanges` as a structured comment to the work-item thread
 *  (same defensive-refetch pattern as ConsoleViewModel.steer). */
class ReviewViewModel(
    private val api: SpecApi,
    connectionId: String,
    private val itemId: String,
    connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()
    private val scope get() = testScope ?: viewModelScope
    private var loadJob: Job? = null
    private val routeConnection = ReactiveRouteConnection(connectionId, connectionState, viewModelScope) { source, snapshot ->
        loadJob?.cancel()
        _sending.value = false
        if (snapshot == null) {
            _state.value = ReviewUiState.Unavailable(if (source is ConnectionState.Error) "Connections unavailable." else "Connection removed.")
        } else {
            loadJob = load(snapshot)
        }
    }

    /** True while a `requestChanges` POST is in flight; the UI disables the button on this to
     *  prevent a double-submit. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** Non-null when the last `requestChanges` send failed; the UI surfaces it and the user
     *  (or the next open) clears it via [clearSendError]. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    fun clearSendError() { _sendError.value = null }

    fun load(): Job = routeConnection.current()?.let(::load) ?: scope.launch { }

    private fun load(snapshot: RouteConnectionSnapshot): Job = scope.launch {
        val next = fetch(snapshot)
        routeConnection.publishIfCurrent(snapshot) { _state.value = next }
    }

    private suspend fun fetch(snapshot: RouteConnectionSnapshot): ReviewUiState = try {
        val conn = snapshot.connection
        val item = api.getItem(conn, itemId)
        coroutineScope {
            val tasks = item.taskSlugs
                .map { async { runCatching { api.getTask(conn, it) }.getOrNull() } }
                .awaitAll().filterNotNull()
            val prs = tasks.flatMap { t ->
                t.prUrls.entries.map { (repo, url) ->
                    PrRow(taskSlug = t.slug, repo = repo, url = url, testStatus = t.testResults[repo])
                }
            }
            // Best-effort: a spec-fetch failure (or a no-spec item) never fails the review page.
            val criteria = item.specId
                ?.let { runCatching { api.getSpec(conn, it) }.getOrNull() }
                ?.acceptanceCriteria
                ?: emptyList()
            ReviewUiState.Content(
                ReviewContent(
                    item, prs, item.threadIds.firstOrNull(),
                    criteria = criteria,
                    prUrls = prs.map { it.url }.distinct(),
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReviewUiState.Failed(e.message ?: "failed to load")
    }

    fun requestChanges(reason: String): Job {
        val snapshot = routeConnection.current() ?: return scope.launch { }
        return scope.launch {
            val tid = (state.value as? ReviewUiState.Content)?.c?.threadId ?: return@launch
            if (!routeConnection.publishIfCurrent(snapshot) {
                _sending.value = true
                _sendError.value = null
            }) return@launch
            try {
                val ok = runCatching { api.postMessage(snapshot.connection, tid, "**Requested changes:** $reason") }.isSuccess
                if (!routeConnection.publishIfCurrent(snapshot) {
                    if (!ok) _sendError.value = "Couldn't send — check your connection and try again."
                }) return@launch
                val next = runCatching { fetch(snapshot) }.getOrNull()
                if (next is ReviewUiState.Content) routeConnection.publishIfCurrent(snapshot) { _state.value = next }
            } finally {
                routeConnection.publishIfCurrent(snapshot) { _sending.value = false }
            }
        }
    }
}
