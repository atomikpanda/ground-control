package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ThreadsSection(
    val workspaceName: String,
    val connectionId: String,
    val threads: Result<List<ThreadSummary>>,
)

/** A thread paired with the connectionId of the workspace it came from. Built directly from
 *  [ThreadsSection]s in [MessagesViewModel.render] so the UI never has to re-derive ownership by
 *  scanning `sections` — that lookup can transiently miss during a live-merge and silently
 *  swallow a tap (see [MessagesUiState.Content.filteredThreads]). */
data class FilteredThread(val connectionId: String, val thread: ThreadSummary)

/** Thread-state filter for the second (state) chip row. Composes with a workspace selection. */
enum class ThreadStateFilter { ALL, UNREAD, NEEDS_YOU }

sealed interface MessagesUiState {
    data object Loading : MessagesUiState
    data object EmptyConfig : MessagesUiState

    /**
     * @param sections raw, unfiltered per-workspace threads (kept for backward-compat call sites).
     * @param selectedConnectionId the workspace-rail selection driving [filteredThreads]; null = All.
     * @param stateFilter the state-chip selection driving [filteredThreads].
     * @param filteredThreads workspace-AND-state filtered threads, newest-first, each paired with its
     *   owning connectionId — what the drill-in list renders and navigates from directly (no
     *   re-lookup against [sections], which can transiently race during a live-merge).
     * @param unreadCount total unseen-thread count across all workspaces (for the sticky card badge).
     * @param unreadCountsByWorkspace unseen-thread count per connectionId (for per-workspace badges).
     */
    data class Content(
        val sections: List<ThreadsSection>,
        val selectedConnectionId: String? = null,
        val stateFilter: ThreadStateFilter = ThreadStateFilter.ALL,
        val filteredThreads: List<FilteredThread> = emptyList(),
        val unreadCount: Int = 0,
        val unreadCountsByWorkspace: Map<String, Int> = emptyMap(),
    ) : MessagesUiState
}

/** Upsert [changed] into [existing] by thread id, then re-sort newest-first by `updatedAt`.
 *  Never replaces the list with just [changed] — older threads are preserved. Pure + directly
 *  unit-testable: this is the exact "long-poll returning only changed threads" bug this fixes. */
internal fun mergeThreadsById(existing: List<ThreadSummary>, changed: List<ThreadSummary>): List<ThreadSummary> {
    val byId = LinkedHashMap<String, ThreadSummary>()
    existing.forEach { byId[it.id] = it }
    changed.forEach { byId[it.id] = it }
    return byId.values.sortedByDescending { it.updatedAt ?: "" }
}

internal fun ThreadSummary.matchesStateFilter(filter: ThreadStateFilter): Boolean = when (filter) {
    ThreadStateFilter.ALL -> true
    ThreadStateFilter.UNREAD -> unseen
    ThreadStateFilter.NEEDS_YOU -> needsYou
}

/** Unread badge count scoped the same way the Home workspace rail scopes the needs-you queue:
 *  the [connectionId] selection's own count, or the cross-workspace total when null (All). Feeds
 *  the Home sticky card's badge so it always matches the currently-selected workspace chip. */
fun MessagesUiState.Content.unreadCountFor(connectionId: String?): Int =
    if (connectionId == null) unreadCount else unreadCountsByWorkspace[connectionId] ?: 0

class MessagesViewModel(
    private val repo: ThreadsRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<MessagesUiState>(MessagesUiState.Loading)
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope

    // Source of truth behind the derived Content fields; re-rendered on every load/merge/filter change.
    private var sections: List<ThreadsSection> = emptyList()
    private var selectedConnectionId: String? = null
    private var stateFilter: ThreadStateFilter = ThreadStateFilter.ALL

    private val pollJobs = mutableMapOf<String, Job>()

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = MessagesUiState.EmptyConfig; return null }
        _state.value = MessagesUiState.Loading
        return scope().launch {
            val results = repo.listAllThreads(connections)
            sections = results.map { ws ->
                ThreadsSection(
                    workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                    connectionId = ws.connection.id,
                    threads = ws.threads,
                )
            }
            render()
        }
    }

    /** Workspace-rail selection (null = All). No-ops until a successful load has produced Content. */
    fun selectWorkspace(connectionId: String?) {
        if (_state.value !is MessagesUiState.Content) return
        selectedConnectionId = connectionId
        render()
    }

    /** State-chip selection (All / Unread / Needs-you). No-ops until a successful load has produced Content. */
    fun selectStateFilter(filter: ThreadStateFilter) {
        if (_state.value !is MessagesUiState.Content) return
        stateFilter = filter
        render()
    }

    /** Most-recently-updated threads, newest-first, optionally scoped to one workspace — unaffected
     *  by the state-chip filter. Feeds the Home sticky card's "top 2-3" peek. */
    fun topThreads(n: Int, connectionId: String? = null): List<ThreadSummary> =
        allThreads(connectionId).take(n)

    /** One long-poll iteration for a single connection: wait for a change since [cursor]; if
     *  threads changed, merge them into that workspace's section and re-render. Returns the next
     *  cursor (unchanged on a network error). Terminating — unit-tested directly; [startLivePolling]
     *  below is a thin looping wrapper (mirrors ConversationViewModel.pollOnce/startPolling). */
    internal suspend fun pollOnce(conn: WorkspaceConnection, cursor: String): String {
        val resp = runCatching { repo.waitForChange(conn, cursor, 25) }.getOrNull() ?: return cursor
        if (resp.threads.isNotEmpty()) {
            sections = sections.map { section ->
                if (section.connectionId != conn.id) section
                else section.copy(
                    threads = Result.success(mergeThreadsById(section.threads.getOrNull() ?: emptyList(), resp.threads))
                )
            }
            render()
        }
        return resp.cursor
    }

    /** Start one live long-poll loop per loaded workspace (idempotent per connection). Call once
     *  after [refresh] has populated sections. Cancelled when the VM's scope is cleared. */
    fun startLivePolling() {
        sections.forEach { section ->
            if (pollJobs[section.connectionId]?.isActive == true) return@forEach
            val conn = connectionsProvider().find { it.id == section.connectionId } ?: return@forEach
            val seed = section.threads.getOrNull()?.mapNotNull { it.updatedAt }?.maxOrNull()
                ?: java.time.Instant.now().toString()
            pollJobs[conn.id] = scope().launch {
                var cursor = seed
                while (isActive) {
                    val next = pollOnce(conn, cursor)
                    if (next == cursor || next.isEmpty()) delay(2000)   // no progress / bad cursor: back off
                    if (next.isNotEmpty()) cursor = next                // never advance `since` to ""
                }
            }
        }
    }

    private fun allThreads(connectionId: String?): List<ThreadSummary> =
        sections
            .filter { connectionId == null || it.connectionId == connectionId }
            .flatMap { it.threads.getOrNull() ?: emptyList() }
            .sortedByDescending { it.updatedAt ?: "" }

    private fun render() {
        val filtered = sections
            .filter { selectedConnectionId == null || it.connectionId == selectedConnectionId }
            .flatMap { section ->
                (section.threads.getOrNull() ?: emptyList()).map { FilteredThread(section.connectionId, it) }
            }
            .filter { it.thread.matchesStateFilter(stateFilter) }
            .sortedByDescending { it.thread.updatedAt ?: "" }
        val unreadByWorkspace = sections.associate { section ->
            section.connectionId to (section.threads.getOrNull()?.count { it.unseen } ?: 0)
        }
        _state.value = MessagesUiState.Content(
            sections = sections,
            selectedConnectionId = selectedConnectionId,
            stateFilter = stateFilter,
            filteredThreads = filtered,
            unreadCount = unreadByWorkspace.values.sum(),
            unreadCountsByWorkspace = unreadByWorkspace,
        )
    }
}
