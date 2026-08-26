package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.InboxAction
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
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
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.ui.inbox.InboxTab
import java.util.UUID
import kotlin.coroutines.coroutineContext
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
    /** The persisted connection stream itself failed; distinct from an empty fleet. */
    data class ConnectionsUnavailable(val cause: Throwable) : MessagesUiState
    data class Content(
        val sections: List<ThreadsSection>,
        val selectedConnectionId: String? = null,
        val tab: InboxTab = InboxTab.ACTIVE,
        val searchQuery: String = "",
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

internal fun ThreadSummary.matchesInboxSearch(query: String): Boolean =
    query.isBlank() || subject.contains(query, ignoreCase = true) || lastMessage.contains(query, ignoreCase = true)

fun MessagesUiState.Content.unreadCountFor(connectionId: String?): Int =
    if (connectionId == null) unreadCount else unreadCountsByWorkspace[connectionId] ?: 0

class MessagesViewModel(
    private val repo: ThreadsRepository,
    private val connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<MessagesUiState>(MessagesUiState.Loading)
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope

    private val owners = mutableMapOf<String, MessageConnectionOwner>()
    private val ownerCollectors = mutableMapOf<MessageConnectionOwner, Job>()
    private val reconcileMutex = Mutex()
    private var reconcileRevision = 0L
    private val mutationLocks = mutableMapOf<String, Mutex>()
    private val mutationLocksMutex = Mutex()

    private var latestConnections: List<WorkspaceConnection> = emptyList()
    private var pollingRequested = false
    private var selectedConnectionId: String? = null
    private var tab = InboxTab.ACTIVE
    private var searchQuery = ""
    private var stateFilter: ThreadStateFilter = ThreadStateFilter.ALL

    init {
        scope().launch {
            connectionState.collectLatest { source ->
                when (source) {
                    ConnectionState.Loading -> {
                        stopAllOwners()
                        _state.value = MessagesUiState.Loading
                    }
                    is ConnectionState.Error -> {
                        stopAllOwners()
                        _state.value = MessagesUiState.ConnectionsUnavailable(source.cause)
                    }
                    is ConnectionState.Ready ->
                        reconcileConnections(source.connections, publishConnections(source.connections))
                }
            }
        }
    }

    private suspend fun stopAllOwners() {
        owners.values.toList().forEach { removeAndCancelOwner(it) }
    }

    private suspend fun publishConnections(current: List<WorkspaceConnection>): Long = reconcileMutex.withLock {
        latestConnections = current
        ++reconcileRevision
    }

    private fun createOwner(connection: WorkspaceConnection): MessageConnectionOwner = MessageConnectionOwner(
        connection = connection,
        fullLoad = { requestConnection ->
            coroutineScope {
                val threads = async {
                    repo.listAllThreads(listOf(requestConnection), tab.filter, searchQuery)
                        .single().threads.getOrThrow()
                }
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
            val response = repo.waitForChange(requestConnection, cursor, 25, tab.filter, searchQuery)
            MessagePollDelta(response.threads, response.removedIds, response.cursor)
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
        coroutineContext.ensureActive()
        reconcileMutex.withLock {
            if (revision != reconcileRevision || latestConnections != current) return
            if (current.isEmpty()) {
                for (owner in owners.values.toSet()) {
                    removeAndCancelOwner(owner)
                }
                renderOwners()
                return
            }

            // A canonical row owns every retained alias in the same snapshot.
            // Reconcile only the canonical view so a later alias cannot recreate
            // an owner that an earlier handoff just retired.
            val activeConnections = current.filter { candidate ->
                current.none { canonical ->
                    canonical.id != candidate.id &&
                        candidate.id in canonical.legacyConnectionIds
                }
            }
            activeConnections.forEach { connection ->
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
                                coroutineContext.ensureActive()
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
                    coroutineContext.ensureActive()
                    owner!!.initialLoad()
                    if (pollingRequested) {
                        coroutineContext.ensureActive()
                        owner!!.startPolling()
                    }
                } else if (owners[connection.id] === owner && owner!!.snapshot.value.connection != connection) {
                    val receipt = try {
                        owner!!.handoffTo(connection)
                    } catch (cancelled: CancellationException) {
                        removeAndCancelOwner(owner!!)
                        throw cancelled
                    }
                    if (receipt != null && revision == reconcileRevision) {
                        try {
                            coroutineContext.ensureActive()
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
                .filter { (id, _) -> activeConnections.none { it.id == id } }
                .map { it.value }
                .toSet()
            ) {
                removeAndCancelOwner(owner)
            }
            selectedConnectionId = selectedConnectionId?.let {
                activeConnections.findByConnectionId(it)?.id ?: it
            }
            renderOwners()
        }
    }

    fun refresh(): Job = scope().launch {
        val startRevision = reconcileMutex.withLock { reconcileRevision }
        val captured = (connectionState.value as? ConnectionState.Ready)?.connections ?: return@launch
        val (target, refreshRevision) = reconcileMutex.withLock {
            if (reconcileRevision != startRevision) return@launch
            latestConnections = captured
            captured to ++reconcileRevision
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

    fun selectInboxTab(tab: InboxTab): Job? {
        if (this.tab == tab) return null
        this.tab = tab
        publishInboxSelection()
        return refresh()
    }

    fun onSearchQueryChange(query: String): Job? {
        if (searchQuery == query) return null
        searchQuery = query
        publishInboxSelection()
        return refresh()
    }

    private fun publishInboxSelection() {
        val current = _state.value as? MessagesUiState.Content ?: return
        _state.value = current.copy(tab = tab, searchQuery = searchQuery)
    }

    fun mutateInbox(connectionId: String, threadId: String, action: InboxAction): Job {
        val mutationId = UUID.randomUUID().toString()
        return scope().launch {
            val connection = latestConnections.findByConnectionId(connectionId) ?: return@launch
            val canonicalConnectionId = connection.id
            mutationLock("$canonicalConnectionId:$threadId").withLock {
                val original = findThread(canonicalConnectionId, threadId) ?: return@withLock
                val owner = owners[canonicalConnectionId]
                val sourceTab = tab
                owner?.beginInboxMutation()
                applyThreadMutation(canonicalConnectionId, threadId, action)
                try {
                    val response = repo.mutateThreadInbox(connection, threadId, action, mutationId)
                    reconcileThreadMutation(canonicalConnectionId, original, response)
                    owner?.endInboxMutation()
                } catch (cancelled: CancellationException) {
                    owner?.endInboxMutation()
                    throw cancelled
                } catch (_: Throwable) {
                    rollbackThreadMutation(canonicalConnectionId, original, action)
                    owner?.endInboxMutation()
                    refresh()
                }
            }
        }
    }

    private suspend fun mutationLock(key: String): Mutex =
        mutationLocksMutex.withLock { mutationLocks.getOrPut(key) { Mutex() } }

    private fun findThread(connectionId: String, threadId: String): ThreadSummary? =
        owners[connectionId]?.snapshot?.value?.threads?.getOrNull()?.firstOrNull { it.id == threadId }

    private suspend fun applyThreadMutation(
        connectionId: String,
        threadId: String,
        action: InboxAction,
    ) {
        owners[connectionId]?.updateThreads { threads ->
            when (action) {
                InboxAction.ARCHIVE, InboxAction.RESTORE -> threads.filterNot { it.id == threadId }
                InboxAction.PIN -> threads.map { if (it.id == threadId) it.copy(pinned = true) else it }
                InboxAction.UNPIN -> threads.map { if (it.id == threadId) it.copy(pinned = false) else it }
            }
        }
        renderOwners()
    }

    private suspend fun rollbackThreadMutation(
        connectionId: String,
        original: ThreadSummary,
        action: InboxAction,
    ) {
        owners[connectionId]?.updateThreads { threads ->
            if (original.inboxState != tab.state) {
                threads.filterNot { it.id == original.id }
            } else when (action) {
                InboxAction.ARCHIVE, InboxAction.RESTORE ->
                    if (threads.any { it.id == original.id }) threads else threads + original
                InboxAction.PIN, InboxAction.UNPIN ->
                    threads.map { if (it.id == original.id) original else it }
            }
        }
        renderOwners()
    }

    private suspend fun reconcileThreadMutation(
        connectionId: String,
        original: ThreadSummary,
        response: Thread,
    ) {
        val resolved = original.copy(
            subject = response.subject,
            updatedAt = response.updatedAt,
            awaitingReply = response.awaitingReply,
            agentSeenAt = response.agentSeenAt,
            workItemId = response.workItemId,
            inboxState = response.inboxState,
            archiveReason = response.archiveReason,
            pinned = response.pinned,
        )
        owners[connectionId]?.updateThreads { threads ->
            if (resolved.inboxState != tab.state) {
                threads.filterNot { it.id == resolved.id }
            } else if (threads.any { it.id == resolved.id }) {
                threads.map { if (it.id == resolved.id) resolved else it }
            } else {
                threads + resolved
            }
        }
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
            .filter { it.thread.inboxState == tab.state }
            .filter { it.thread.matchesInboxSearch(searchQuery) }
            .filter { tab == InboxTab.ARCHIVED || it.thread.matchesStateFilter(stateFilter) }
            .sortedByDescending { it.thread.updatedAt ?: "" }
        val unreadByWorkspace = sections.associate { section ->
            section.connectionId to section.threads.getOrDefault(emptyList())
                .count { it.inboxState == InboxTab.ACTIVE.state && it.unseen }
        }
        _state.value = MessagesUiState.Content(
            sections = sections,
            selectedConnectionId = selectedConnectionId,
            tab = tab,
            searchQuery = searchQuery,
            stateFilter = stateFilter,
            filteredThreads = filtered,
            groups = groupThreadsByWorkItem(filtered.map { it.thread }, visibleSections.flatMap { it.items }),
            unreadCount = unreadByWorkspace.values.sum(),
            unreadCountsByWorkspace = unreadByWorkspace,
        )
    }
}
