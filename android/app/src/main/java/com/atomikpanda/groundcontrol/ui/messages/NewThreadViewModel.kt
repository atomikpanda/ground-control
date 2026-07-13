package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NewThreadMessage {
    data class Created(val connectionId: String, val threadId: String) : NewThreadMessage
    data class Error(val text: String) : NewThreadMessage
}

enum class CaptureKind { QUICK_NOTE, BRAINSTORM_SPEC }

data class NewThreadUiState(
    val connections: List<WorkspaceConnection> = emptyList(),
    val selectedConnectionId: String? = null,
    val subject: String = "",
    val text: String = "",
    val kind: CaptureKind = CaptureKind.QUICK_NOTE,
    val inFlight: Boolean = false,
    val isLoading: Boolean = true,
    val message: NewThreadMessage? = null,
)

/** Auto-select only when there is exactly one connection; otherwise require a pick. */
fun defaultSelection(connections: List<WorkspaceConnection>): String? = connections.singleOrNull()?.id

/** True when a thread can be created: text non-blank, a connection selected, not in-flight. */
fun canCreate(state: NewThreadUiState): Boolean =
    state.text.isNotBlank() && state.selectedConnectionId != null && !state.inFlight

class NewThreadViewModel(
    private val repo: ThreadsRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(NewThreadUiState())
    val state: StateFlow<NewThreadUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope

    fun load() {
        val conns = connectionsProvider()
        _state.value = _state.value.copy(
            connections = conns,
            selectedConnectionId = conns.firstOrNull { it.id == _state.value.selectedConnectionId }?.id
                ?: defaultSelection(conns),
            isLoading = false,
            message = null,
        )
    }

    fun onSubjectChange(s: String) { _state.value = _state.value.copy(subject = s) }
    fun onTextChange(t: String) { _state.value = _state.value.copy(text = t) }
    fun onSelectConnection(id: String) { _state.value = _state.value.copy(selectedConnectionId = id) }
    fun onSelectKind(k: CaptureKind) { _state.value = _state.value.copy(kind = k) }
    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    fun create(): Job? {
        val s = _state.value
        if (!canCreate(s)) return null
        val conn = s.connections.firstOrNull { it.id == s.selectedConnectionId } ?: return null
        _state.value = s.copy(inFlight = true, message = null)
        val subject = s.subject.trim().ifBlank { null }
        return scope().launch {
            runCatching {
                when (s.kind) {
                    CaptureKind.QUICK_NOTE -> repo.createThread(conn, s.text.trim(), subject)
                    CaptureKind.BRAINSTORM_SPEC -> repo.captureBrainstorm(conn, s.text.trim(), subject, java.util.UUID.randomUUID().toString())
                }
            }
                .onSuccess { thread ->
                    _state.value = _state.value.copy(
                        inFlight = false,
                        text = "",
                        subject = "",
                        message = NewThreadMessage.Created(conn.id, thread.id),
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(inFlight = false, message = NewThreadMessage.Error(errorText(t)))
                }
        }
    }

    private fun errorText(t: Throwable): String = when (t) {
        is AuthException -> "Token rejected — fix this connection in Settings."
        else -> "Couldn't reach the workspace — try again."
    }
}
