package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.notify.NeedsYouCanceller
import com.atomikpanda.groundcontrol.notify.NoopNeedsYouCanceller
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Most recent journal entries to surface in the in-thread activity strip (MOS-224):
 *  "last 2" per the recorded operator decision. */
private const val ACTIVITY_STRIP_ENTRY_COUNT = 2

sealed interface ConversationUiState {
    data object Loading : ConversationUiState
    data class Unavailable(val message: String) : ConversationUiState
    data class Error(val kind: ErrorKind, val message: String) : ConversationUiState
    data class Content(
        val thread: Thread,
        val inFlight: Boolean = false,
        /** Non-null when the last send failed; the compose bar surfaces it and
         *  restores the user's typed text so it isn't silently lost. */
        val sendError: String? = null,
        /** Last [ACTIVITY_STRIP_ENTRY_COUNT] task-journal entries (oldest-first) for the
         *  in-thread activity strip -- empty when the thread has no `task_slug` or the
         *  journal fetch failed. Never surfaced as an [Error]: a missing/unavailable
         *  journal degrades to an empty strip, not a broken conversation. */
        val journal: List<JournalEntry> = emptyList(),
    ) : ConversationUiState
}

class ConversationViewModel(
    private val repo: ThreadsRepository,
    private val routeConnectionId: String,
    val threadId: String,
    connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
    private val canceller: NeedsYouCanceller = NoopNeedsYouCanceller,
) : ViewModel() {


    private val _state = MutableStateFlow<ConversationUiState>(ConversationUiState.Loading)
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    fun onDraftChange(text: String) { _draft.value = text }

    private fun scope() = testScope ?: viewModelScope

    private var pollJob: Job? = null
    private var pollingRequested = false
    private var loadJob: Job? = null
    private val routeConnection = ReactiveRouteConnection(routeConnectionId, connectionState, viewModelScope) { source, snapshot ->
        pollJob?.cancel()
        loadJob?.cancel()
        if (snapshot == null) {
            _state.value = ConversationUiState.Unavailable(if (source is ConnectionState.Error) "Connections unavailable." else "Connection removed.")
        } else {
            loadJob = load(snapshot)
        }
    }
    /** Canonical identity for lifecycle consumers; changes when a route alias is adopted. */
    val resolvedConnectionId: StateFlow<String?> get() = routeConnection.resolvedConnectionId

    fun load(): Job = routeConnection.current()?.let(::load) ?: scope().launch { }

    private fun load(snapshot: com.atomikpanda.groundcontrol.ui.RouteConnectionSnapshot): Job {
        routeConnection.publishIfCurrent(snapshot) { _state.value = ConversationUiState.Loading }
        return scope().launch {
            runCatching { repo.getThread(snapshot.connection, threadId) }
                .onSuccess { thread ->
                    val journal = fetchJournal(thread.taskSlug, snapshot.connection)
                    routeConnection.publishIfCurrent(snapshot) {
                        _state.value = ConversationUiState.Content(thread, journal = journal)
                        markSeen(thread, snapshot.connection)
                        canceller.cancel(snapshot.connection.id, threadId)
                        if (pollingRequested) startPolling()
                    }
                }
                .onFailure { t ->
                    routeConnection.publishIfCurrent(snapshot) {
                        _state.value = ConversationUiState.Error(t.toKind(), t.message ?: "error")
                    }
                }
        }
    }

    /** Like [runCatching], but rethrows [CancellationException] so scope cancellation propagates
     *  (mirrors WorkspaceViewModel/HomeFeedRepository's `catchingApi` elsewhere in the app). */
    private inline fun <T> catchingApi(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    /** Journal entries for the activity strip (MOS-224): the last [ACTIVITY_STRIP_ENTRY_COUNT]
     *  entries for [taskSlug], oldest-first -- an empty list when the thread has no linked task
     *  (skips the fetch entirely) or the fetch fails, so a flaky/unavailable journal never turns
     *  into a broken conversation. */
    private suspend fun fetchJournal(taskSlug: String?, conn: WorkspaceConnection): List<JournalEntry> {
        if (taskSlug == null) return emptyList()
        return catchingApi { repo.getJournal(conn, taskSlug) }
            .getOrDefault(emptyList())
            .takeLast(ACTIVITY_STRIP_ENTRY_COUNT)
    }

    /** Best-effort: mark this thread seen up to its loaded high-water timestamp.
     *  Never mutates UI state — a failed mark must not disturb the conversation. */
    private fun markSeen(thread: Thread, conn: WorkspaceConnection) {
        scope().launch { runCatching { repo.markSeen(conn, threadId, thread.updatedAt) } }
    }

    /** One long-poll iteration: wait for a change since `cursor`; if THIS thread
     *  changed (and no send is in flight), re-fetch it. Returns the next cursor
     *  (unchanged on a network error so the caller can back off). Terminating —
     *  unit-tested directly; the loop below is a thin wrapper.
     *
     *  Also refreshes the MOS-224 activity strip's journal on this same cadence —
     *  even when the thread's *messages* haven't changed — so a task that's actively
     *  journaling progress doesn't go stale just because the agent hasn't replied yet
     *  (reuses this poll; no separate tight loop).
     *
     *  The journal fetch must never gate reply delivery (Greptile finding on PR #40):
     *  when the thread itself changed, the refreshed thread/messages are applied to
     *  state immediately, carrying the last-known journal forward as a placeholder;
     *  the journal refresh for *this* tick is kicked off separately (fire-and-forget)
     *  and only updates the `journal` field once it resolves. A slow or hanging
     *  `/journal` fetch (up to the ~10s OkHttp read timeout) would otherwise delay the
     *  user seeing the agent's reply. The unchanged-thread tick below has nothing else
     *  to deliver, so it keeps awaiting the journal inline. */
    internal suspend fun pollOnce(cursor: String): String {
        val snapshot = routeConnection.current() ?: return cursor
        val resp = runCatching { repo.waitForChange(snapshot.connection, cursor, 25) }.getOrNull() ?: return cursor
        val cur = _state.value
        if (cur is ConversationUiState.Content && !cur.inFlight) {
            if (resp.threads.any { it.id == threadId }) {
                runCatching { repo.getThread(snapshot.connection, threadId) }
                    .onSuccess { thread ->
                        routeConnection.publishIfCurrent(snapshot) {
                            val latest = _state.value as? ConversationUiState.Content
                                ?: return@publishIfCurrent
                            _state.value = latest.copy(thread = thread)
                            refreshJournalAsync(thread.taskSlug)
                        }
                    }
            } else {
                val journal = fetchJournal(cur.thread.taskSlug, snapshot.connection)
                routeConnection.publishIfCurrent(snapshot) {
                    val latest = _state.value as? ConversationUiState.Content ?: return@publishIfCurrent
                    if (journal != latest.journal) _state.value = latest.copy(journal = journal)
                }
            }
        }
        return resp.cursor
    }

    /** Fire-and-forget refresh of the activity-strip journal after a live thread refresh in
     *  [pollOnce]: launched separately from (and not awaited by) the primary thread/messages
     *  update so a slow or hanging `/journal` fetch can never delay reply delivery. Only
     *  applies the fetched journal if the state is still `Content` by the time it resolves —
     *  a concurrent load()/send() landing in the meantime must win over a stale background
     *  update. */
    private fun refreshJournalAsync(taskSlug: String?) {
        val snapshot = routeConnection.current() ?: return
        scope().launch {
            val journal = fetchJournal(taskSlug, snapshot.connection)
            routeConnection.publishIfCurrent(snapshot) {
                (_state.value as? ConversationUiState.Content)?.let { latest ->
                    _state.value = latest.copy(journal = journal)
                }
            }
        }
    }
    /** Start the live-reply loop (idempotent). Seeds from the loaded thread's
     *  high-water `updatedAt`; returns early until a thread is loaded, so the
     *  `since` is always a valid ISO timestamp (never "" the server would 422 on).
     *  Cancelled when the VM's scope is cleared. */
    fun startPolling() {
        pollingRequested = true
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

    /** Post a message; no-op if blank or a send is already in flight. Returns null if skipped. */
    fun send(text: String): Job? {
        val snapshot = routeConnection.current() ?: return null
        if (text.isBlank()) return null
        val current = _state.value as? ConversationUiState.Content ?: return null
        if (current.inFlight) return null
        if (!routeConnection.publishIfCurrent(snapshot) {
            _state.value = current.copy(inFlight = true, sendError = null)
        }) return null
        return scope().launch {
            runCatching { repo.postMessage(snapshot.connection, threadId, text) }
                .onSuccess { updatedThread ->
                    routeConnection.publishIfCurrent(snapshot) {
                        _draft.value = ""
                        _state.value = ConversationUiState.Content(updatedThread, inFlight = false, journal = current.journal)
                    }
                }
                .onFailure { t ->
                    routeConnection.publishIfCurrent(snapshot) {
                        _state.value = current.copy(inFlight = false, sendError = t.toSendError())
                    }
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
