package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ThreadsSection(
    val workspaceName: String,
    val connectionId: String,
    val threads: Result<List<ThreadSummary>>,
    val items: List<WorkItemSummary> = emptyList(),
)

data class FilteredThread(val connectionId: String, val thread: ThreadSummary)

enum class ThreadStateFilter { ALL, UNREAD, NEEDS_YOU }

sealed interface MessagesUiState {
    data object Loading : MessagesUiState
    data object EmptyConfig : MessagesUiState
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

    private val owners = mutableMapOf<String, MessageConnectionOwner>()
    private val ownerCollectors = mutableMapOf<MessageConnectionOwner, Job>()
    private val reconcileMutex = Mutex()
    private var reconcileRevision = 0L
    private var latestConnections: List<WorkspaceConnection> = emptyList()
    private var pollingRequested = false
    private var selectedConnectionId: String? = null
    private var stateFilter: ThreadStateFilter = ThreadStateFilter.ALL

    init {
        scope().launch {
            connections.distinctUntilChanged().collectLatest { current ->
                reconcileRevision += 1
                reconcileConnections(current, reconcileRevision)
            }
        }
    }

    private fun createOwner(connection: WorkspaceConnection): MessageConnectionOwner = MessageConnectionOwner(
        connection = connection,
        fullLoad = { requestConnection ->
            coroutineScope {
                val threads = async { repo.listThreadsFor(requestConnection) }
                val items = async {
                    try {
                        repo.listAllItems(listOf(requestConnection))[requestConnection.id].orEmpty()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
                MessageFullLoad(threads.await(), items.await())
            }
        },
        poll = { requestConnection, cursor ->
            val response = repo.waitForChange(requestConnection, cursor, 25)
            MessagePollDelta(response.threads, response.cursor)
        },
        scope = scope(),
    )

    private fun observe(owner: MessageConnectionOwner) {
        ownerCollectors.getOrPut(owner) {
            scope().launch { owner.snapshot.collect { renderOwners() } }
        }
    }

    private suspend fun stopObserving(owner: MessageConnectionOwner) {
        ownerCollectors.remove(owner)?.let { collector ->
            collector.cancel()
            collector.join()
        }
    }

    /** Cleanup is non-cancellable: a superseding connection emission must not leave a detached
     * owner retrying, nor leave a cancelled owner reachable under a replacement key. */
    private suspend fun removeAndCancelOwner(owner: MessageConnectionOwner) = withContext(NonCancellable) {
        owners.filterValues { it === owner }.keys.toList().forEach(owners::remove)
        owner.cancel()
        stopObserving(owner)
    }

    /** Reconciles exactly one latest connection snapshot. collectLatest cancels a blocked handoff
     * before it can resume obsolete work; its owner is cancelled in that path, so detached jobs
     * cannot outlive the reconciliation that abandoned them. */
    private suspend fun reconcileConnections(current: List<WorkspaceConnection>, revision: Long) {
        reconcileMutex.withLock {
            latestConnections = current
            if (current.isEmpty()) {
                for (owner in owners.values.toSet()) {
                    removeAndCancelOwner(owner)
                }
                renderOwners()
                return
            }

            current.forEach { connection ->
                var owner = owners[connection.id]
                if (owner == null) {
                    val legacyId = connection.legacyConnectionIds.firstOrNull { owners.containsKey(it) }
                    if (legacyId != null) {
                        owner = owners[legacyId]
                        try {
                            // Keep the legacy owner mapped until its sole collector is gone. A
                            // superseding emission therefore sees either a mapped owner or the
                            // guaranteed cleanup below, never an unobserved detached owner.
                            withContext(NonCancellable) { stopObserving(owner!!) }
                            owners.remove(legacyId)
                            val receipt = owner!!.handoffTo(connection)
                            if (receipt != null && revision == reconcileRevision) {
                                owners[connection.id] = owner!!
                                observe(owner!!)
                                owner!!.resumeAfterHandoff(receipt)
                            } else {
                                removeAndCancelOwner(owner!!)
                                owner = null
                            }
                        } catch (cancelled: CancellationException) {
                            removeAndCancelOwner(owner!!)
                            throw cancelled
                        }
                    }
                }
                if (owner == null) {
                    owner = createOwner(connection)
                    owners[connection.id] = owner!!
                    observe(owner!!)
                    owner!!.initialLoad()
                    if (pollingRequested) owner!!.startPolling()
                } else if (owners[connection.id] === owner && owner!!.snapshot.value.connection != connection) {
                    val receipt = try {
                        owner!!.handoffTo(connection)
                    } catch (cancelled: CancellationException) {
                        removeAndCancelOwner(owner!!)
                        throw cancelled
                    }
                    if (receipt != null && revision == reconcileRevision) {
                        try {
                            owner!!.resumeAfterHandoff(receipt)
                        } catch (cancelled: CancellationException) {
                            removeAndCancelOwner(owner!!)
                            throw cancelled
                        }
                    } else {
                        removeAndCancelOwner(owner!!)
                        owner = null
                    }
                }
            }

            for (owner in owners.entries
                .filter { (id, _) -> current.none { it.id == id } }
                .map { it.value }
                .toSet()
            ) {
                removeAndCancelOwner(owner)
            }
            selectedConnectionId = selectedConnectionId?.let { current.findByConnectionId(it)?.id ?: it }
            renderOwners()
        }
    }

    fun refresh(): Job = scope().launch {
        val startRevision = reconcileMutex.withLock { reconcileRevision }
        val captured = connections.first()
        val (target, refreshRevision) = reconcileMutex.withLock {
            val target = if (reconcileRevision == startRevision) captured else latestConnections
            target to ++reconcileRevision
        }
        reconcileConnections(target, refreshRevision)
        if (target.isEmpty() || latestConnections != target || reconcileRevision != refreshRevision) return@launch
        owners.values.map { it.refresh() }.joinAll()
        if (latestConnections == target && reconcileRevision == refreshRevision) renderOwners()
    }

    fun selectWorkspace(connectionId: String?) {
        if (_state.value !is MessagesUiState.Content) return
        selectedConnectionId = connectionId
        selectedConnectionId = latestConnections.findByConnectionId(connectionId ?: "")?.id ?: connectionId
        renderOwners()
    }

    fun selectStateFilter(filter: ThreadStateFilter) {
        if (_state.value !is MessagesUiState.Content) return
        stateFilter = filter
        renderOwners()
    }
    fun topThreads(n: Int, connectionId: String? = null): List<ThreadSummary> =
        allThreads(connectionId).take(n)

    /** Retained for callers/tests that exercise a single poll turn without starting the loop. */
    internal suspend fun pollOnce(conn: WorkspaceConnection, cursor: String): String {
        val next = owners[conn.id]?.pollOnceForTest(cursor) ?: cursor
        renderOwners()
        return next
    }

    fun startLivePolling() {
        pollingRequested = true
        owners.values.forEach { it.startPolling() }
    }

    internal fun ownerSnapshot(connectionId: String): MessageConnectionSnapshot? = owners[connectionId]?.snapshot?.value
    internal fun ownerForTest(connectionId: String): MessageConnectionOwner? = owners[connectionId]

    private fun allThreads(connectionId: String?): List<ThreadSummary> = sections()
        .filter { connectionId == null || it.connectionId == connectionId }
        .flatMap { it.threads.getOrDefault(emptyList()) }
        .sortedByDescending { it.updatedAt ?: "" }

    private fun sections(): List<ThreadsSection> = latestConnections.mapNotNull { connection ->
        owners[connection.id]?.snapshot?.value?.let { snapshot ->
            ThreadsSection(
                workspaceName = snapshot.connection.workspaceName.ifBlank { snapshot.connection.baseUrl },
                connectionId = connection.id,
                threads = snapshot.threads,
                items = snapshot.items,
            )
        }
    }

    private fun renderOwners() {
        if (latestConnections.isEmpty()) {
            _state.value = MessagesUiState.EmptyConfig
            return
        }
        val sections = sections()
        if (sections.isEmpty() || sections.all {
                owners[it.connectionId]?.snapshot?.value?.phase == MessageConnectionSnapshot.Phase.INITIAL_LOADING
            }) {
            _state.value = MessagesUiState.Loading
            return
        }
        val visibleSections = sections.filter { selectedConnectionId == null || it.connectionId == selectedConnectionId }
        val filtered = visibleSections
            .flatMap { section -> section.threads.getOrDefault(emptyList()).map { FilteredThread(section.connectionId, it) } }
            .filter { it.thread.matchesStateFilter(stateFilter) }
            .sortedByDescending { it.thread.updatedAt ?: "" }
        val unreadByWorkspace = sections.associate { section ->
            section.connectionId to section.threads.getOrDefault(emptyList()).count { it.unseen }
        }
        _state.value = MessagesUiState.Content(
            sections = sections,
            selectedConnectionId = selectedConnectionId,
            stateFilter = stateFilter,
            filteredThreads = filtered,
            groups = groupThreadsByWorkItem(filtered.map { it.thread }, visibleSections.flatMap { it.items }),
            unreadCount = unreadByWorkspace.values.sum(),
            unreadCountsByWorkspace = unreadByWorkspace,
        )
    }
}
