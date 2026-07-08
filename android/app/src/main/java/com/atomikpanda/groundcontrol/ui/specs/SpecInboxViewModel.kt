package com.atomikpanda.groundcontrol.ui.specs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
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

data class GroupBlock(val group: SpecGroup, val specs: List<SpecSummary>)

data class WorkspaceSection(
    val workspaceName: String,
    val connectionId: String,
    val groups: Result<List<GroupBlock>>,   // failure → show error chip
)

sealed interface InboxUiState {
    data object Loading : InboxUiState
    data object EmptyConfig : InboxUiState                       // no connections configured
    data class Content(val sections: List<WorkspaceSection>) : InboxUiState
}

/** `connectionsProvider` is a suspend-free snapshot supplier (the repo/DataStore feeds it). */
class SpecInboxViewModel(
    private val repo: SpecRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,   // null → use viewModelScope; inject in tests
) : ViewModel() {

    private val _state = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    /** Returns the Job so callers (tests) can join/await completion if needed. */
    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = InboxUiState.EmptyConfig; return null }
        _state.value = InboxUiState.Loading
        return (testScope ?: viewModelScope).launch {
            val results = repo.listAllSpecs(connections)
            _state.value = InboxUiState.Content(
                results.map { ws ->
                    WorkspaceSection(
                        workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                        connectionId = ws.connection.id,
                        groups = ws.specs.map { specs -> toGroupBlocks(specs) },
                    )
                }
            )
        }
    }

    private fun toGroupBlocks(specs: List<SpecSummary>): List<GroupBlock> {
        val byGroup = specs.groupBy { groupForStatus(it.status) }
        return orderedGroups().mapNotNull { g ->
            byGroup[g]?.takeIf { it.isNotEmpty() }?.let { GroupBlock(g, it) }
        }   // empty groups + null-group (archived/unknown) omitted
    }

    /** Swipe-to-archive: remove the spec from its workspace section immediately (optimistic)
     *  and archive it server-side, restoring the pre-archive state on failure. Same
     *  capture-then-restore + rethrow-CancellationException shape as FarmViewModel's
     *  setUnattended, but restores a whole state snapshot rather than a single field — removing
     *  a list entry (not toggling a value) is what's being undone here. */
    fun archiveSpec(connectionId: String, specId: String): Job = (testScope ?: viewModelScope).launch {
        val conn = connectionsProvider().find { it.id == connectionId } ?: return@launch
        val previous = _state.value
        removeSpec(connectionId, specId)
        runCatching { repo.archiveSpec(conn, specId) }.onFailure {
            if (it is CancellationException) throw it
            _state.value = previous
        }
    }

    private fun removeSpec(connectionId: String, specId: String) {
        val current = _state.value as? InboxUiState.Content ?: return
        _state.value = current.copy(
            sections = current.sections.map { section ->
                if (section.connectionId != connectionId) {
                    section
                } else {
                    section.copy(groups = section.groups.map { blocks ->
                        blocks.mapNotNull { block ->
                            val filtered = block.specs.filterNot { it.id == specId }
                            filtered.takeIf { it.isNotEmpty() }?.let { block.copy(specs = it) }
                        }
                    })
                }
            },
        )
    }
}
