package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThreadsSection(
    val workspaceName: String,
    val connectionId: String,
    val threads: Result<List<ThreadSummary>>,
)

sealed interface MessagesUiState {
    data object Loading : MessagesUiState
    data object EmptyConfig : MessagesUiState
    data class Content(val sections: List<ThreadsSection>) : MessagesUiState
}

class MessagesViewModel(
    private val repo: ThreadsRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<MessagesUiState>(MessagesUiState.Loading)
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = MessagesUiState.EmptyConfig; return null }
        _state.value = MessagesUiState.Loading
        return (testScope ?: viewModelScope).launch {
            val results = repo.listAllThreads(connections)
            _state.value = MessagesUiState.Content(results.map { ws ->
                ThreadsSection(
                    workspaceName = ws.connection.workspaceName.ifBlank { ws.connection.baseUrl },
                    connectionId = ws.connection.id,
                    threads = ws.threads,
                )
            })
        }
    }
}
