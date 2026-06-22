package com.atomikpanda.groundcontrol.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CaptureMessage {
    data class Created(val specId: String) : CaptureMessage
    data class Error(val text: String) : CaptureMessage
}

data class CaptureUiState(
    val connections: List<WorkspaceConnection> = emptyList(),
    val selectedConnectionId: String? = null,
    val title: String = "",
    val repos: String = "",
    val inFlight: Boolean = false,
    val isLoading: Boolean = true,
    val message: CaptureMessage? = null,
)

/** Auto-select only when there is exactly one connection; otherwise require a pick. */
fun defaultSelection(connections: List<WorkspaceConnection>): String? = connections.singleOrNull()?.id

/** Comma-split the affected-repos field: trim each and drop blanks. */
fun parseRepos(input: String): List<String> =
    input.split(",").map { it.trim() }.filter { it.isNotEmpty() }

fun canCreate(state: CaptureUiState): Boolean =
    state.title.isNotBlank() && state.selectedConnectionId != null && !state.inFlight

class CaptureViewModel(
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val api: SpecApi,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()
    private fun scope() = testScope ?: viewModelScope

    fun load() {
        val conns = connectionsProvider()
        _state.value = _state.value.copy(
            connections = conns,
            selectedConnectionId = conns.firstOrNull { it.id == _state.value.selectedConnectionId }?.id ?: defaultSelection(conns),
            isLoading = false,
            message = null,
        )
    }

    fun onTitleChange(t: String) { _state.value = _state.value.copy(title = t) }
    fun onReposChange(r: String) { _state.value = _state.value.copy(repos = r) }
    fun onSelectConnection(id: String) { _state.value = _state.value.copy(selectedConnectionId = id) }
    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    fun create(): Job? {
        val s = _state.value
        if (!canCreate(s)) return null
        val conn = s.connections.firstOrNull { it.id == s.selectedConnectionId } ?: return null
        _state.value = s.copy(inFlight = true, message = null)
        return scope().launch {
            runCatching { api.createSpec(conn, s.title.trim(), parseRepos(s.repos)) }
                .onSuccess { rec ->
                    _state.value = _state.value.copy(
                        inFlight = false, title = "", repos = "",
                        message = CaptureMessage.Created(rec.id),
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(inFlight = false, message = CaptureMessage.Error(errorText(t)))
                }
        }
    }

    private fun errorText(t: Throwable): String = when (t) {
        is ApiConflictException -> "A spec named that already exists."
        is AuthException -> "Token rejected — fix this connection in Settings."
        else -> "Couldn't reach the workspace — try again."
    }
}
