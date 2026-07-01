package com.atomikpanda.groundcontrol.ui.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
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
}
