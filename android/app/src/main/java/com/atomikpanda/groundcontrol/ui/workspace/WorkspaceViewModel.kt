package com.atomikpanda.groundcontrol.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import com.atomikpanda.groundcontrol.ui.RouteConnectionSnapshot
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WorkspaceUiState {
    data object Loading : WorkspaceUiState
    data class Unavailable(val message: String) : WorkspaceUiState
    data class Content(
        val threads: List<ThreadSummary>,
        val specs: List<SpecSummary>,
        val tasks: List<TaskSummary>,
        val errored: Boolean,
        val rePairNeeded: Boolean = false,
    ) : WorkspaceUiState
}

class WorkspaceViewModel(
    private val api: SpecApi,
    connectionId: String,
    connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()
    private val scope get() = testScope ?: viewModelScope
    private var refreshJob: Job? = null
    private val routeConnection = ReactiveRouteConnection(connectionId, connectionState, viewModelScope) { source, snapshot ->
        refreshJob?.cancel()
        if (snapshot == null) {
            _state.value = WorkspaceUiState.Unavailable(if (source is ConnectionState.Error) "Connections unavailable." else "Connection removed.")
        } else {
            refreshJob = refresh(snapshot)
        }
    }

    /** Like [runCatching], but rethrows [CancellationException] so scope cancellation propagates. */
    private inline fun <T> catchingApi(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    fun refresh(): Job = routeConnection.current()?.let(::refresh) ?: scope.launch { }

    private fun refresh(snapshot: RouteConnectionSnapshot): Job = scope.launch {
        val conn = snapshot.connection
        val threads = async { catchingApi { api.listThreads(conn) } }
        val specs = async { catchingApi { api.listSpecs(conn) } }
        val tasks = async { catchingApi { api.listTasks(conn) } }
        val t = threads.await()
        val s = specs.await()
        val k = tasks.await()
        val rePairNeeded = listOf(t, s, k).any { it.exceptionOrNull() is RePairNeededException }
        routeConnection.publishIfCurrent(snapshot) {
            _state.value = WorkspaceUiState.Content(
                threads = t.getOrDefault(emptyList()),
                specs = s.getOrDefault(emptyList()),
                tasks = k.getOrDefault(emptyList()),
                errored = t.isFailure || s.isFailure || k.isFailure,
                rePairNeeded = rePairNeeded,
            )
        }
    }
}
