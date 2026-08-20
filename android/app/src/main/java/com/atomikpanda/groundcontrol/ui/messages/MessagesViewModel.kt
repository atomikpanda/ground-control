package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val LIVE_POLL_RETRY_DELAY_MILLIS = 2_000L

data class ThreadsSection(
    val workspaceName: String,
    val connectionId: String,
    val threads: Result<List<ThreadSummary>>,
    // WorkItems for this workspace, used only to label the by-WorkItem groups (title + kind).
    // Empty until the first successful /items fetch; threads then fall into "Other".
    val items: List<WorkItemSummary> = emptyList(),
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
     * @param groups the same [filteredThreads] bucketed by owning WorkItem (null -> "Other"),
     *   ordered by newest thread — what the messages surface renders as sections.
     * @param unreadCount total unseen-thread count across all workspaces (for the sticky card badge).
     * @param unreadCountsByWorkspace unseen-thread count per connectionId (for per-workspace badges).
     */
    data class Content(
        val sections: List<ThreadsSection>,
        val selectedConnectionId: String? = null,
        val stateFilter: ThreadStateFilter = ThreadStateFilter.ALL,
        val filteredThreads: List<FilteredThread> = emptyList(),
        val groups: List<WorkItemThreadGroup> = emptyList(),
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
    private val connections: Flow<List<WorkspaceConnection>>,
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
    private var latestConnections: List<WorkspaceConnection> = emptyList()
    private var livePollingStarted = false

    private fun canonicalizeLoadedIdentities(currentConnections: List<WorkspaceConnection>) {
        val canonicalSections = sections.map { section ->
            currentConnections.findByConnectionId(section.connectionId)?.let { conn ->
                section.copy(
                    workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                    connectionId = conn.id,
                )
            } ?: section
        }
        sections = canonicalSections
            .groupBy { it.connectionId }
            .values
            .map { matching ->
                if (matching.size == 1) return@map matching.single()
                val successfulThreads = matching.mapNotNull { it.threads.getOrNull() }
                val threads = if (successfulThreads.isEmpty()) {
                    matching.first().threads
                } else {
                    val threadsById = LinkedHashMap<String, ThreadSummary>()
                    successfulThreads.forEach { loaded ->
                        loaded.forEach { candidate ->
                            val existing = threadsById[candidate.id]
                            // Match mergeThreadsById: null is oldest; an equal timestamp is an upsert.
                            if (existing == null || (candidate.updatedAt ?: "") >= (existing.updatedAt ?: "")) {
                                threadsById[candidate.id] = candidate
                            }
                        }
                    }
                    Result.success(threadsById.values.sortedByDescending { it.updatedAt ?: "" })
                }
                val itemsById = LinkedHashMap<String, WorkItemSummary>()
                matching.forEach { section ->
                    section.items.forEach { candidate ->
                        val existing = itemsById[candidate.id]
                        if (existing == null || (candidate.updatedAt ?: "") >= (existing.updatedAt ?: "")) {
                            itemsById[candidate.id] = candidate
                        }
                    }
                }
                matching.first().copy(
                    threads = threads,
                    items = itemsById.values.toList(),
                )
            }
        selectedConnectionId = selectedConnectionId?.let { selected ->
            currentConnections.findByConnectionId(selected)?.id ?: selected
        }
    }

    init {
        scope().launch {
            connections.distinctUntilChanged().collect { currentConnections ->
                latestConnections = currentConnections
                if (livePollingStarted) reconcileLivePolling(currentConnections)
            }
        }
    }

    fun refresh(): Job? = scope().launch {
        val connections = this@MessagesViewModel.connections.first()
        latestConnections = connections
        if (connections.isEmpty()) {
            _state.value = MessagesUiState.EmptyConfig
            return@launch
        }
        _state.value = MessagesUiState.Loading
        // Fetch threads + items concurrently so the spinner isn't blocked on both round-trips
        // end-to-end (items are best-effort and shouldn't serialize the primary load).
        val (results, itemsByConn) = coroutineScope {
            val threadsDeferred = async { repo.listAllThreads(connections) }
            val itemsDeferred = async { repo.listAllItems(connections) }
            threadsDeferred.await() to itemsDeferred.await()
        }
        sections = results.map { ws ->
            ThreadsSection(
                workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                connectionId = ws.connection.id,
                threads = ws.threads,
                items = itemsByConn[ws.connection.id] ?: emptyList(),
            )
        }
        if (livePollingStarted) {
            reconcileLivePolling(latestConnections)
        } else {
            canonicalizeLoadedIdentities(latestConnections)
            render()
        }
    }

    /** Workspace-rail selection (null = All). No-ops until a successful load has produced Content. */
    fun selectWorkspace(connectionId: String?) {
        if (_state.value !is MessagesUiState.Content) return
        selectedConnectionId = connectionId
        canonicalizeLoadedIdentities(latestConnections)
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

    private fun reconcileLivePolling(currentConnections: List<WorkspaceConnection>) {
        if (sections.isEmpty()) return
        val jobs = pollJobs.entries.iterator()
        while (jobs.hasNext()) {
            val entry = jobs.next()
            if (currentConnections.findByConnectionId(entry.key)?.id != entry.key) {
                entry.value.cancel()
                jobs.remove()
            }
        }
        canonicalizeLoadedIdentities(currentConnections)
        render()

        sections.forEach { section ->
            if (pollJobs[section.connectionId]?.isActive == true) return@forEach
            val conn = currentConnections.findByConnectionId(section.connectionId) ?: return@forEach
            val seed = section.threads.getOrNull()?.mapNotNull { it.updatedAt }?.maxOrNull()
                ?: java.time.Instant.now().toString()
            pollJobs[conn.id] = scope().launch {
                var cursor = seed
                while (isActive) {
                    val next = pollOnce(conn, cursor)
                    if (next == cursor || next.isEmpty()) delay(LIVE_POLL_RETRY_DELAY_MILLIS)
                    if (next.isNotEmpty()) cursor = next
                }
            }
        }
    }

    /** Start one live long-poll loop per loaded workspace. The connection Flow collector keeps the
     * latest decoded DataStore snapshot and reconciles immediately on each distinct ownership
     * change, so host adoption cancels retired jobs, coalesces newly-shared sections, and starts the
     * canonical route without blocking or polling the main dispatcher. */
    fun startLivePolling() {
        livePollingStarted = true
        reconcileLivePolling(latestConnections)
    }

    private fun allThreads(connectionId: String?): List<ThreadSummary> =
        sections
            .filter { connectionId == null || it.connectionId == connectionId }
            .flatMap { it.threads.getOrNull() ?: emptyList() }
            .sortedByDescending { it.updatedAt ?: "" }

    private fun render() {
        val visibleSections = sections
            .filter { selectedConnectionId == null || it.connectionId == selectedConnectionId }
        val filtered = visibleSections
            .flatMap { section ->
                (section.threads.getOrNull() ?: emptyList()).map { FilteredThread(section.connectionId, it) }
            }
            .filter { it.thread.matchesStateFilter(stateFilter) }
            .sortedByDescending { it.thread.updatedAt ?: "" }
        // Bucket the (already workspace+state filtered) threads by owning WorkItem. WorkItem ids
        // are globally unique, so combining items across the visible workspaces is safe.
        val groups = groupThreadsByWorkItem(
            filtered.map { it.thread },
            visibleSections.flatMap { it.items },
        )
        val unreadByWorkspace = sections.associate { section ->
            section.connectionId to (section.threads.getOrNull()?.count { it.unseen } ?: 0)
        }
        _state.value = MessagesUiState.Content(
            sections = sections,
            selectedConnectionId = selectedConnectionId,
            stateFilter = stateFilter,
            filteredThreads = filtered,
            groups = groups,
            unreadCount = unreadByWorkspace.values.sum(),
            unreadCountsByWorkspace = unreadByWorkspace,
        )
    }
}
