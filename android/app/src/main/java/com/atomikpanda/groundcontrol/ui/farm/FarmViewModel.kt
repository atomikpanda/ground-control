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
     *  post-then-refetch would silently drop the change) and rolls back on failure — mirrors
     *  [refresh]'s runCatching style but is a mutation, not a fetch, so it doesn't re-throw
     *  CancellationException. */
    fun setUnattended(item: WorkItemSummary, on: Boolean): Job = (testScope ?: viewModelScope).launch {
        applyToItem(item.id) { it.copy(unattended = on) }
        val ok = runCatching { api.setUnattended(conn, item.id, on) }.isSuccess
        if (!ok) applyToItem(item.id) { it.copy(unattended = !on) }
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
