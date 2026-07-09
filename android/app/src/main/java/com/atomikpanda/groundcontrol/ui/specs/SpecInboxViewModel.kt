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
     *  and archive it server-side. Rethrows CancellationException, same as FarmViewModel's
     *  setUnattended, so scope cancellation isn't swallowed as an ordinary failure.
     *
     *  On failure, re-inserts only this one spec back into the section's *current* groups rather
     *  than restoring a whole pre-archive snapshot — a whole-snapshot restore would resurrect any
     *  spec removed by a concurrent refresh or a different, concurrently-succeeding archive that
     *  landed while this request was still in flight. */
    fun archiveSpec(connectionId: String, specId: String): Job = (testScope ?: viewModelScope).launch {
        val conn = connectionsProvider().find { it.id == connectionId } ?: return@launch
        val archived = findSpec(connectionId, specId)
        removeSpec(connectionId, specId)
        runCatching { repo.archiveSpec(conn, specId) }.onFailure {
            if (it is CancellationException) throw it
            if (archived != null) reinsertSpec(connectionId, archived)
        }
    }

    private fun findSpec(connectionId: String, specId: String): SpecSummary? =
        (_state.value as? InboxUiState.Content)?.sections
            ?.firstOrNull { it.connectionId == connectionId }
            ?.groups?.getOrNull()
            ?.flatMap { it.specs }
            ?.firstOrNull { it.id == specId }

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

    /** Re-inserts a single archived spec back into its section, recomputed against the section's
     *  CURRENT specs (not a captured-at-call-time snapshot) so concurrent changes — another
     *  archive that succeeded, or a refresh — aren't clobbered. A no-op if the spec is already
     *  present (e.g. a concurrent refresh already brought it back). */
    private fun reinsertSpec(connectionId: String, spec: SpecSummary) {
        val current = _state.value as? InboxUiState.Content ?: return
        _state.value = current.copy(
            sections = current.sections.map { section ->
                if (section.connectionId != connectionId) {
                    section
                } else {
                    section.copy(groups = section.groups.map { blocks ->
                        val specs = blocks.flatMap { it.specs }
                        if (specs.any { it.id == spec.id }) blocks else toGroupBlocks(specs + spec)
                    })
                }
            },
        )
    }
}
