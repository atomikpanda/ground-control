package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStatePublicationFence
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val connectionError: Throwable? = null,
    val message: NewThreadMessage? = null,
)

/** Auto-select only when there is exactly one connection; otherwise require a pick. */
fun defaultSelection(connections: List<WorkspaceConnection>): String? = connections.singleOrNull()?.id

/** True when a thread can be created: text non-blank, a connection selected, not in-flight. */
fun canCreate(state: NewThreadUiState): Boolean =
    state.text.isNotBlank() && state.selectedConnectionId != null && !state.inFlight

class NewThreadViewModel(
    private val repo: ThreadsRepository,
    private val connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(NewThreadUiState())
    val state: StateFlow<NewThreadUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope

    init {
        scope().launch {
            connectionState.collectLatest { source ->
                when (source) {
                    ConnectionState.Loading -> _state.value = _state.value.copy(
                        connections = emptyList(), selectedConnectionId = null, isLoading = true, connectionError = null, inFlight = _state.value.inFlight,
                    )
                    is ConnectionState.Error -> _state.value = _state.value.copy(
                        connections = emptyList(), selectedConnectionId = null, isLoading = false, connectionError = source.cause, inFlight = _state.value.inFlight,
                    )
                    is ConnectionState.Ready -> applyConnections(source.connections)
                }
            }
        }
    }

    fun load() {
        when (val source = connectionState.value) {
            ConnectionState.Loading -> _state.value = _state.value.copy(
                connections = emptyList(), selectedConnectionId = null, isLoading = true,
                connectionError = null, inFlight = false, message = null,
            )
            is ConnectionState.Error -> _state.value = _state.value.copy(
                connections = emptyList(), selectedConnectionId = null, isLoading = false,
                connectionError = source.cause, inFlight = false, message = null,
            )
            is ConnectionState.Ready -> applyConnections(source.connections)
        }
    }

    private fun applyConnections(conns: List<WorkspaceConnection>) {
        val previous = _state.value
        val selected = previous.selectedConnectionId
            ?.let { conns.findByConnectionId(it) }
            ?.id
            ?: defaultSelection(conns)
        val created = previous.message as? NewThreadMessage.Created
        val createdSource = created?.let { previous.connections.findByConnectionId(it.connectionId) }
        val createdCurrent = created?.let { conns.findByConnectionId(it.connectionId) }
        val message = if (
            created != null &&
            createdSource != null &&
            createdCurrent != null &&
            (createdCurrent.id != createdSource.id || createdCurrent == createdSource)
        ) {
            NewThreadMessage.Created(createdCurrent.id, created.threadId)
        } else null
        _state.value = previous.copy(
            connections = conns,
            selectedConnectionId = selected,
            isLoading = false,
            connectionError = null,
            message = message,
        )
    }

    private fun publishSuccessfulCreate(conn: WorkspaceConnection, threadId: String) {
        synchronized(ConnectionStatePublicationFence.lock) {
            val canonical = (connectionState.value as? ConnectionState.Ready)
                ?.connections
                ?.findByConnectionId(conn.id)
            _state.value = if (canonical == null || (canonical.id == conn.id && canonical != conn)) {
                _state.value.copy(inFlight = false)
            } else {
                _state.value.copy(
                    inFlight = false,
                    text = "",
                    subject = "",
                    message = NewThreadMessage.Created(canonical.id, threadId),
                )
            }
        }
    }

    fun onSubjectChange(s: String) { _state.value = _state.value.copy(subject = s) }
    fun onTextChange(t: String) { _state.value = _state.value.copy(text = t) }
    fun onSelectConnection(id: String) {
        val canonicalId = _state.value.connections.findByConnectionId(id)?.id ?: id
        _state.value = _state.value.copy(selectedConnectionId = canonicalId)
    }
    fun onSelectKind(k: CaptureKind) { _state.value = _state.value.copy(kind = k) }
    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    fun create(): Job? {
        val request = synchronized(ConnectionStatePublicationFence.lock) {
            val current = _state.value
            if (!canCreate(current)) return null
            val ready = connectionState.value as? ConnectionState.Ready ?: return null
            val selectedConnectionId = current.selectedConnectionId ?: return null
            val connection = ready.connections.findByConnectionId(selectedConnectionId) ?: return null
            _state.value = current.copy(
                connections = ready.connections,
                selectedConnectionId = connection.id,
                inFlight = true,
                message = null,
            )
            current to connection
        }
        val (requestState, connection) = request
        val subject = requestState.subject.trim().ifBlank { null }
        return scope().launch {
            runCatching {
                val latest = (connectionState.value as? ConnectionState.Ready)
                    ?.connections
                    ?.findByConnectionId(connection.id)
                check(latest == connection) { "Connection changed before create." }
                when (requestState.kind) {
                    CaptureKind.QUICK_NOTE ->
                        repo.createThread(connection, requestState.text.trim(), subject)
                    CaptureKind.BRAINSTORM_SPEC ->
                        repo.captureBrainstorm(
                            connection,
                            requestState.text.trim(),
                            subject,
                            java.util.UUID.randomUUID().toString(),
                        )
                }
            }.onSuccess { thread ->
                publishSuccessfulCreate(connection, thread.id)
            }.onFailure { error ->
                synchronized(ConnectionStatePublicationFence.lock) {
                    val current = (connectionState.value as? ConnectionState.Ready)
                        ?.connections
                        ?.findByConnectionId(connection.id)
                    _state.value = if (current == connection) {
                        _state.value.copy(
                            inFlight = false,
                            message = NewThreadMessage.Error(errorText(error)),
                        )
                    } else {
                        _state.value.copy(inFlight = false)
                    }
                }
            }
        }
    }

    private fun errorText(t: Throwable): String = when (t) {
        is AuthException -> "Token rejected — fix this connection in Settings."
        else -> "Couldn't reach the workspace — try again."
    }
}
