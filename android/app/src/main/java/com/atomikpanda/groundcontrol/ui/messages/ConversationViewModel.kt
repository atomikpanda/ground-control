package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConversationUiState {
    data object Loading : ConversationUiState
    data class Error(val kind: ErrorKind, val message: String) : ConversationUiState
    data class Content(
        val thread: Thread,
        val inFlight: Boolean = false,
        /** Non-null when the last send failed; the compose bar surfaces it and
         *  restores the user's typed text so it isn't silently lost. */
        val sendError: String? = null,
    ) : ConversationUiState
}

class ConversationViewModel(
    private val repo: ThreadsRepository,
    private val conn: WorkspaceConnection,
    private val threadId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ConversationUiState>(ConversationUiState.Loading)
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    fun onDraftChange(text: String) { _draft.value = text }

    private fun scope() = testScope ?: viewModelScope

    private var pollJob: Job? = null

    fun load(): Job? {
        _state.value = ConversationUiState.Loading
        return scope().launch {
            runCatching { repo.getThread(conn, threadId) }
                .onSuccess { thread -> _state.value = ConversationUiState.Content(thread) }
                .onFailure { t -> _state.value = ConversationUiState.Error(t.toKind(), t.message ?: "error") }
        }
    }

    /** One long-poll iteration: wait for a change since `cursor`; if THIS thread
     *  changed (and no send is in flight), re-fetch it. Returns the next cursor
     *  (unchanged on a network error so the caller can back off). Terminating —
     *  unit-tested directly; the loop below is a thin wrapper. */
    internal suspend fun pollOnce(cursor: String): String {
        val resp = runCatching { repo.waitForChange(conn, cursor, 25) }.getOrNull() ?: return cursor
        if (resp.threads.any { it.id == threadId }) {
            val cur = _state.value
            if (cur is ConversationUiState.Content && !cur.inFlight) {
                runCatching { repo.getThread(conn, threadId) }
                    // Preserve a visible send-error banner across a live refresh — an
                    // agent reply arriving must not silently erase "couldn't send".
                    .onSuccess { _state.value = ConversationUiState.Content(it, sendError = cur.sendError) }
            }
        }
        return resp.cursor
    }

    /** Start the live-reply loop (idempotent). Seeds from the loaded thread's
     *  high-water `updatedAt`; returns early until a thread is loaded, so the
     *  `since` is always a valid ISO timestamp (never "" the server would 422 on).
     *  Cancelled when the VM's scope is cleared. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        // Only poll a loaded conversation; if the server omitted updated_at, fall
        // back to "now" so live-reply still starts (never a silent no-op).
        val content = _state.value as? ConversationUiState.Content ?: return
        val seed = content.thread.updatedAt ?: java.time.Instant.now().toString()
        pollJob = scope().launch {
            var cursor = seed
            while (isActive) {
                val next = pollOnce(cursor)
                if (next == cursor || next.isEmpty()) delay(2000)   // no progress / bad cursor: back off
                if (next.isNotEmpty()) cursor = next                // never advance `since` to ""
            }
        }
    }

    /** Ask the host agent (via the mailbox) to turn this thread into a spec. */
    fun requestSpec(): Job? = send("Please turn this thread into a spec.")

    /** Post a message; no-op if blank or a send is already in flight. Returns null if skipped. */
    fun send(text: String): Job? {
        if (text.isBlank()) return null
        val current = _state.value as? ConversationUiState.Content ?: return null
        if (current.inFlight) return null
        // Clear any prior send error when a new attempt starts.
        _state.value = current.copy(inFlight = true, sendError = null)
        return scope().launch {
            runCatching { repo.postMessage(conn, threadId, text) }
                .onSuccess { updatedThread ->
                    _draft.value = ""
                    _state.value = ConversationUiState.Content(updatedThread, inFlight = false)
                }
                .onFailure { t ->
                    // Keep the existing thread, drop inFlight, and surface the failure so
                    // the UI can show it and restore the user's text (instead of silently
                    // re-enabling an empty compose bar).
                    _state.value = current.copy(inFlight = false, sendError = t.toSendError())
                }
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }

    private fun Throwable.toSendError(): String = when (this) {
        is AuthException -> "Token rejected — fix this connection in Settings."
        is NotFoundException -> "This conversation is no longer available."
        else -> "Couldn't send. Check your connection and try again."
    }
}
