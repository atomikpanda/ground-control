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
}
