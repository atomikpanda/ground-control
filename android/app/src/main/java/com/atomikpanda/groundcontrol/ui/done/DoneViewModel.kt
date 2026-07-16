package com.atomikpanda.groundcontrol.ui.done

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
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

data class DoneContent(
    val item: WorkItemSummary,
    val tasks: List<TaskSummary>,
    val reposTouched: List<String>,
    val completedAt: String?,      // max task.finishedAt, else item.updatedAt
    val review: ReviewSummary?,    // null when the item has no spec
    val criteria: List<ReviewCriterion> = emptyList(),
    val prUrls: List<String> = emptyList(),
)

sealed interface DoneUiState {
    data object Loading : DoneUiState
    data class Content(val c: DoneContent) : DoneUiState
    data class Failed(val reason: String) : DoneUiState
}

/** Completion summary cockpit for a finished work item: fans out GET /items/{id} into its
 *  tasks, aggregates `affected_repos` into `reposTouched`, derives `completedAt` from the
 *  max task `finished_at` (falling back to the item's `updated_at`), and optionally fetches
 *  the spec review summary (same fan-out pattern as ReviewViewModel). */
class DoneViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val itemId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<DoneUiState>(DoneUiState.Loading)
    val state: StateFlow<DoneUiState> = _state.asStateFlow()
    private val scope get() = testScope ?: viewModelScope

    fun load(): Job = scope.launch { _state.value = fetch() }

    private suspend fun fetch(): DoneUiState = try {
        val item = api.getItem(conn, itemId)
        coroutineScope {
            val tasks = item.taskSlugs
                .map { async { runCatching { api.getTask(conn, it) }.getOrNull() } }
                .awaitAll().filterNotNull()
            val reposTouched = tasks.flatMap { it.affectedRepos }.distinct()
            val completedAt = tasks.mapNotNull { it.finishedAt }.maxOrNull() ?: item.updatedAt
            // One review fetch yields both the summary and the acceptance criteria (with evidence).
            val reviewRecord = item.specId?.let { runCatching { api.getReview(conn, it) }.getOrNull() }
            val prUrls = tasks.flatMap { it.prUrls.values }.distinct()
            DoneUiState.Content(
                DoneContent(
                    item, tasks, reposTouched, completedAt,
                    review = reviewRecord?.summary,
                    criteria = reviewRecord?.acceptanceCriteria ?: emptyList(),
                    prUrls = prUrls,
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DoneUiState.Failed(e.message ?: "failed to load")
    }
}
