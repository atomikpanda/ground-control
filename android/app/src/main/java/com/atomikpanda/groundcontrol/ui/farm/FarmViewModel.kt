package com.atomikpanda.groundcontrol.ui.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface FarmUiState {
    data object Loading : FarmUiState
    data class Content(val groups: List<PhaseGroup>, val errored: Boolean) : FarmUiState
}

/** Single-workspace farm: loads GET /items for one connection and bins by phase.
 *  Shape mirrors WorkspaceViewModel (concrete conn + optional testScope). */
class FarmViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<FarmUiState>(FarmUiState.Loading)
    val state: StateFlow<FarmUiState> = _state.asStateFlow()

    fun refresh(): Job = (testScope ?: viewModelScope).launch {
        _state.value = FarmUiState.Loading
        _state.value = runCatching { api.listItems(conn) }.fold(
            onSuccess = { FarmUiState.Content(groupByPhase(it), errored = false) },
            onFailure = {
                if (it is CancellationException) throw it
                FarmUiState.Content(emptyList(), errored = true)
            },
        )
    }

    /** Toggle a work item's unattended flag. Updates local state immediately (the server's
     *  own list/get responses don't echo `unattended` back into the summary yet, so a
     *  post-then-refetch would silently drop the change) and rolls back to the pre-toggle
     *  value on failure — mirrors [refresh]'s runCatching style, including re-throwing
     *  CancellationException so scope cancellation isn't swallowed as an ordinary failure. */
    fun setUnattended(item: WorkItemSummary, on: Boolean): Job = (testScope ?: viewModelScope).launch {
        val original = item.unattended
        applyToItem(item.id) { it.copy(unattended = on) }
        runCatching { api.setUnattended(conn, item.id, on) }.onFailure {
            if (it is CancellationException) throw it
            applyToItem(item.id) { summary -> summary.copy(unattended = original) }
        }
    }

    /** Quick actions on the WorkItem card: "Mark done" (`phase = "done"`) and "Reopen"
     *  (`phase = null`, clearing the override so the item returns to its derived phase).
     *  Sets `phaseOverride` immediately (the server's list/get responses don't echo the field
     *  back into a full summary either) and rolls back to the pre-call value on failure —
     *  same shape as [setUnattended], including re-throwing CancellationException so scope
     *  cancellation isn't swallowed as an ordinary failure. */
    fun setItemPhase(item: WorkItemSummary, phase: String?): Job = (testScope ?: viewModelScope).launch {
        val original = item.phaseOverride
        applyToItem(item.id) { it.copy(phaseOverride = phase) }
        runCatching { api.setItemPhase(conn, item.id, phase) }.onFailure {
            if (it is CancellationException) throw it
            applyToItem(item.id) { summary -> summary.copy(phaseOverride = original) }
        }
    }

    private fun applyToItem(id: String, transform: (WorkItemSummary) -> WorkItemSummary) {
        val current = _state.value as? FarmUiState.Content ?: return
        _state.value = current.copy(
            groups = current.groups.map { g ->
                g.copy(items = g.items.map { if (it.id == id) transform(it) else it })
            },
        )
    }
}
