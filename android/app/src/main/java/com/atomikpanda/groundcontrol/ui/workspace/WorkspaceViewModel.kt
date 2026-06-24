package com.atomikpanda.groundcontrol.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
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
    data class Content(
        val threads: List<ThreadSummary>,
        val specs: List<SpecSummary>,
        val tasks: List<TaskSummary>,
        val errored: Boolean,
    ) : WorkspaceUiState
}

class WorkspaceViewModel(
    private val api: SpecApi,
    private val conn: WorkspaceConnection,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    /** Like [runCatching], but rethrows [CancellationException] so scope cancellation propagates. */
    private inline fun <T> catchingApi(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    fun refresh(): Job = (testScope ?: viewModelScope).launch {
        val threads = async { catchingApi { api.listThreads(conn) } }
        val specs = async { catchingApi { api.listSpecs(conn) } }
        val tasks = async { catchingApi { api.listTasks(conn) } }
        val t = threads.await()
        val s = specs.await()
        val k = tasks.await()
        _state.value = WorkspaceUiState.Content(
            threads = t.getOrDefault(emptyList()),
            specs = s.getOrDefault(emptyList()),
            tasks = k.getOrDefault(emptyList()),
            errored = t.isFailure || s.isFailure || k.isFailure,
        )
    }
}
