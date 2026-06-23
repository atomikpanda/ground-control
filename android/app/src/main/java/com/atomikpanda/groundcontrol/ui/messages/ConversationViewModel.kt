package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConversationUiState {
    data object Loading : ConversationUiState
    data class Error(val kind: ErrorKind, val message: String) : ConversationUiState
    data class Content(val thread: Thread, val inFlight: Boolean = false) : ConversationUiState
}

class ConversationViewModel(
    private val repo: ThreadsRepository,
    private val conn: WorkspaceConnection,
    private val threadId: String,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ConversationUiState>(ConversationUiState.Loading)
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private fun scope() = testScope ?: viewModelScope

    fun load(): Job? {
        _state.value = ConversationUiState.Loading
        return scope().launch {
            runCatching { repo.getThread(conn, threadId) }
                .onSuccess { thread -> _state.value = ConversationUiState.Content(thread) }
                .onFailure { t -> _state.value = ConversationUiState.Error(t.toKind(), t.message ?: "error") }
        }
    }

    /** Post a message; no-op if blank or a send is already in flight. Returns null if skipped. */
    fun send(text: String): Job? {
        if (text.isBlank()) return null
        val current = _state.value as? ConversationUiState.Content ?: return null
        if (current.inFlight) return null
        _state.value = current.copy(inFlight = true)
        return scope().launch {
            runCatching { repo.postMessage(conn, threadId, text) }
                .onSuccess { updatedThread ->
                    _state.value = ConversationUiState.Content(updatedThread, inFlight = false)
                }
                .onFailure { t ->
                    // On send failure, restore content without inFlight (keep existing thread)
                    _state.value = current.copy(inFlight = false)
                }
        }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }
}
