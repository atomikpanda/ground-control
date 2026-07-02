package com.atomikpanda.groundcontrol.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
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
data class ReviewContent(val item: WorkItemSummary, val prs: List<PrRow>, val threadId: String?)

sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data class Content(val c: ReviewContent) : ReviewUiState
    data class Failed(val reason: String) : ReviewUiState
}

/** Review cockpit for a single work item: fans out GET /items/{id} into its tasks,
 *  aggregates each task's `pr_urls`/`test_results` into per-repo `PrRow`s, and
 *  posts `requestChanges` as a structured comment to the work-item thread
 *  (same defensive-refetch pattern as ConsoleViewModel.steer). */
class ReviewViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val itemId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()
    private val scope get() = testScope ?: viewModelScope

    fun load(): Job = scope.launch { _state.value = fetch() }

    private suspend fun fetch(): ReviewUiState = try {
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
            ReviewUiState.Content(ReviewContent(item, prs, item.threadIds.firstOrNull()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReviewUiState.Failed(e.message ?: "failed to load")
    }

    fun requestChanges(reason: String): Job = scope.launch {
        val tid = (state.value as? ReviewUiState.Content)?.c?.threadId ?: return@launch
        runCatching { api.postMessage(conn, tid, "**Requested changes:** $reason") }
        // defensive refetch (mirror ConsoleViewModel.steer): don't drop to Failed on a transient error
        val next = runCatching { fetch() }.getOrNull()
        if (next is ReviewUiState.Content) _state.value = next
    }
}
