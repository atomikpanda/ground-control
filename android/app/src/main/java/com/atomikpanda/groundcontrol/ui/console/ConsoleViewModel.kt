package com.atomikpanda.groundcontrol.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.ReviewSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class ConsoleContent(
    val item: WorkItemSummary,
    val tasks: List<TaskSummary>,
    val journal: List<JournalEntry>,
    val review: ReviewSummary?,          // null when the item has no spec
    val activeDecision: Decision?,       // last unanswered decision on the work-item thread
    val activeDecisionText: String?,     // the active decision message's question text
    val threadId: String?,
)

sealed interface ConsoleUiState {
    data object Loading : ConsoleUiState
    data class Content(val c: ConsoleContent) : ConsoleUiState
    data class Failed(val reason: String) : ConsoleUiState
}

/** Console for a single work item: fans out GET /items/{id} into its tasks,
 *  journal, spec review, and work-item thread, then computes the active
 *  decision (if any) the same way ConversationScreen does. `steer`/`answerOption`
 *  both reuse the existing thread-message send path. */
class ConsoleViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val itemId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ConsoleUiState>(ConsoleUiState.Loading)
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()
    private val scope get() = testScope ?: viewModelScope

    /** True while a `steer`/`answerOption` POST is in flight; the UI disables the Send
     *  control on this to prevent a double-submit. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** Non-null when the last `steer`/`answerOption` send failed; the UI surfaces it and the
     *  user (or the next attempt) clears it via [clearSendError]. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    fun clearSendError() { _sendError.value = null }

    fun load(): Job = scope.launch { _state.value = fetch() }

    /** Periodic refresh; cancel by cancelling the returned Job (bind to the composable's lifecycle). */
    fun startPolling(intervalMs: Long = 4000): Job = scope.launch {
        while (isActive) {
            delay(intervalMs)
            val next = runCatching { fetch() }.getOrNull()
            if (next is ConsoleUiState.Content) _state.value = next
        }
    }

    private suspend fun fetch(): ConsoleUiState = try {
        val item = api.getItem(conn, itemId)
        coroutineScope {
            val tasks = item.taskSlugs.map { async { runCatching { api.getTask(conn, it) }.getOrNull() } }
            val threadId = item.threadIds.firstOrNull()
            val thread = threadId?.let { runCatching { api.getThread(conn, it) }.getOrNull() }
            val journal = item.taskSlugs.firstOrNull()
                ?.let { runCatching { api.getJournal(conn, it) }.getOrNull() } ?: emptyList()
            val review = item.specId?.let { runCatching { api.getReview(conn, it).summary }.getOrNull() }
            val activeDecisionMessage = thread?.let { activeDecisionMessage(it) }
            ConsoleUiState.Content(ConsoleContent(
                item = item,
                tasks = tasks.awaitAll().filterNotNull(),
                journal = journal,
                review = review,
                activeDecision = activeDecisionMessage?.decision,
                activeDecisionText = activeDecisionMessage?.text,
                threadId = threadId,
            ))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ConsoleUiState.Failed(e.message ?: "failed to load")
    }

    /** Last unanswered decision message after the last human message (same rule as
     *  ConversationScreen) — carries both the `Decision` payload and its question `text`,
     *  since `DecisionCard` needs the question to render alongside the options. */
    private fun activeDecisionMessage(thread: Thread): Message? {
        val lastHuman = thread.messages.indexOfLast { it.role == "human" }
        return thread.messages.drop(lastHuman + 1)
            .lastOrNull { it.kind == "decision" }
    }

    fun answerOption(text: String): Job = steer(text)   // an option tap is a plain human reply

    fun steer(text: String): Job = scope.launch {
        val tid = (state.value as? ConsoleUiState.Content)?.c?.threadId ?: return@launch
        _sending.value = true
        _sendError.value = null
        try {
            val ok = runCatching { api.postMessage(conn, tid, text) }.isSuccess
            if (!ok) _sendError.value = "Couldn't send — check your connection and try again."
            // defensive refetch: don't drop to Failed on a transient error
            val next = runCatching { fetch() }.getOrNull()
            if (next is ConsoleUiState.Content) _state.value = next
        } finally {
            _sending.value = false
        }
    }
}
