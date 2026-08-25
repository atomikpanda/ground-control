package com.atomikpanda.groundcontrol.ui.specs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.InboxAction
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.groupForStatus
import com.atomikpanda.groundcontrol.data.orderedGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import com.atomikpanda.groundcontrol.ui.inbox.InboxTab
import java.util.UUID

data class GroupBlock(val group: SpecGroup, val specs: List<SpecSummary>)

data class WorkspaceSection(
    val workspaceName: String,
    val connectionId: String,
    val groups: Result<List<GroupBlock>>,   // failure → show error chip
)

sealed interface InboxUiState {
    data object Loading : InboxUiState
    data object EmptyConfig : InboxUiState                       // no connections configured
    data class Content(
        val sections: List<WorkspaceSection>,
        val tab: InboxTab = InboxTab.ACTIVE,
        val searchQuery: String = "",
    ) : InboxUiState
}

/** `connectionsProvider` is a suspend-free snapshot supplier (the repo/DataStore feeds it). */
class SpecInboxViewModel(
    private val repo: SpecRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,   // null → use viewModelScope; inject in tests
) : ViewModel() {

    private val _state = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val state: StateFlow<InboxUiState> = _state.asStateFlow()
    private var tab = InboxTab.ACTIVE
    private var searchQuery = ""
    private var refreshRevision = 0L


    /** Returns the Job so callers (tests) can join/await completion if needed. */
    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) {
            _state.value = InboxUiState.EmptyConfig
            return null
        }
        val requestTab = tab
        val requestQuery = searchQuery
        val revision = ++refreshRevision
        if (_state.value !is InboxUiState.Content) _state.value = InboxUiState.Loading
        return (testScope ?: viewModelScope).launch {
            val results = repo.listAllSpecs(connections, requestTab.filter, requestQuery)
            if (revision != refreshRevision || requestTab != tab || requestQuery != searchQuery) return@launch
            _state.value = InboxUiState.Content(
                sections = results.map { ws ->
                    WorkspaceSection(
                        workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                        connectionId = ws.connection.id,
                        groups = ws.specs.map { specs -> toGroupBlocks(specs) },
                    )
                },
                tab = requestTab,
                searchQuery = requestQuery,
            )
        }
    }

    fun selectInboxTab(tab: InboxTab) {
        if (this.tab == tab) return
        this.tab = tab
        refresh()
    }

    fun onSearchQueryChange(query: String) {
        if (searchQuery == query) return
        searchQuery = query
        refresh()
    }

    private fun toGroupBlocks(specs: List<SpecSummary>): List<GroupBlock> {
        val byGroup = specs.groupBy { groupForStatus(it.status) }
        return orderedGroups().mapNotNull { g ->
            byGroup[g]?.takeIf { it.isNotEmpty() }?.let { GroupBlock(g, it) }
        }   // empty groups + null-group (archived/unknown) omitted
    }

    /**
     * Applies one durable inbox action. Archive and restore remove the item from the current
     * tab; pin and unpin update only that one item. A failed mutation restores only the entity
     * still present in the current state, never a stale whole-list snapshot.
     */
    fun mutateInbox(connectionId: String, specId: String, action: InboxAction): Job {
        val mutationId = UUID.randomUUID().toString()
        return (testScope ?: viewModelScope).launch {
            val conn = connectionsProvider().findByConnectionId(connectionId) ?: return@launch
            val canonicalConnectionId = conn.id
            replaceConnectionAlias(connectionId, conn)
            val original = findSpec(canonicalConnectionId, specId) ?: return@launch
            val sourceTab = tab
            applyMutation(canonicalConnectionId, specId, action)
            try {
                repo.mutateSpecInbox(conn, specId, action, mutationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (tab == sourceTab) rollbackMutation(canonicalConnectionId, original, action)
            }
        }
    }

    private fun replaceConnectionAlias(requestedId: String, canonical: WorkspaceConnection) {
        if (requestedId == canonical.id) return
        val current = _state.value as? InboxUiState.Content ?: return
        _state.value = current.copy(
            sections = current.sections.map { section ->
                if (section.connectionId != requestedId) section else section.copy(
                    workspaceName = canonical.workspaceName.ifBlank { canonical.baseUrl },
                    connectionId = canonical.id,
                )
            },
        )
    }

    private fun findSpec(connectionId: String, specId: String): SpecSummary? =
        (_state.value as? InboxUiState.Content)?.sections
            ?.firstOrNull { it.connectionId == connectionId }
            ?.groups?.getOrNull()
            ?.flatMap { it.specs }
            ?.firstOrNull { it.id == specId }

    private fun applyMutation(connectionId: String, specId: String, action: InboxAction) {
        updateSpec(connectionId) { specs ->
            when (action) {
                InboxAction.ARCHIVE, InboxAction.RESTORE -> specs.filterNot { it.id == specId }
                InboxAction.PIN -> specs.map { if (it.id == specId) it.copy(pinned = true) else it }
                InboxAction.UNPIN -> specs.map { if (it.id == specId) it.copy(pinned = false) else it }
            }
        }
    }

    private fun rollbackMutation(connectionId: String, original: SpecSummary, action: InboxAction) {
        updateSpec(connectionId) { specs ->
            when (action) {
                InboxAction.ARCHIVE, InboxAction.RESTORE ->
                    if (specs.any { it.id == original.id }) specs else specs + original
                InboxAction.PIN, InboxAction.UNPIN ->
                    specs.map { if (it.id == original.id) original else it }
            }
        }
    }

    private fun updateSpec(
        connectionId: String,
        transform: (List<SpecSummary>) -> List<SpecSummary>,
    ) {
        val current = _state.value as? InboxUiState.Content ?: return
        _state.value = current.copy(
            sections = current.sections.map { section ->
                if (section.connectionId != connectionId) section else section.copy(
                    groups = section.groups.map { blocks ->
                        val specs = blocks.flatMap { it.specs }
                        transform(specs)
                            .takeIf { it.isNotEmpty() }
                            ?.let(::toGroupBlocks)
                            ?: emptyList()
                    },
                )
            },
        )
    }
}
