package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.NotFoundException
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.lastResolvedMessageIndex
import com.atomikpanda.groundcontrol.ui.ReactiveRouteConnection
import com.atomikpanda.groundcontrol.ui.RouteConnectionSnapshot
import com.atomikpanda.groundcontrol.ui.specdetail.ErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The single unresolved prompt this screen can answer. Its message id is the target identity. */
data class ActiveDecisionPrompt(
    val messageId: String,
    val text: String,
    val decision: Decision? = null,
)

sealed interface DecisionUiState {
    data object Loading : DecisionUiState
    data class Unavailable(val message: String) : DecisionUiState
    data class Error(val kind: ErrorKind, val message: String) : DecisionUiState
    data class Content(
        val thread: Thread,
        val prompt: ActiveDecisionPrompt,
        val inFlight: Boolean = false,
        val sendError: String? = null,
    ) : DecisionUiState
    /** The displayed message is not the latest actionable prompt anymore. */
    data class NoLongerActionable(val thread: Thread?, val message: String) : DecisionUiState
    /** `postMessage` returned successfully; this confirms receipt, not agent execution. */
    data class ResponseRecorded(val thread: Thread) : DecisionUiState
}

/**
 * Focused, one-question response flow. It deliberately re-fetches the thread immediately before
 * posting: the HTTP message protocol has no compare-and-swap precondition, so the client can only
 * refuse a target it has observed as stale rather than claim an atomic server-side guarantee.
 */
class DecisionViewModel(
    private val repo: ThreadsRepository,
    connectionId: String,
    val threadId: String,
    connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<DecisionUiState>(DecisionUiState.Loading)
    val state: StateFlow<DecisionUiState> = _state.asStateFlow()

    private fun scope(): CoroutineScope = testScope ?: viewModelScope

    private var loadJob: Job? = null
    private var submitJob: Job? = null
    private val routeConnection = ReactiveRouteConnection(connectionId, connectionState, viewModelScope) { source, snapshot ->
        loadJob?.cancel()
        submitJob?.cancel()
        if (snapshot == null) {
            _state.value = DecisionUiState.Unavailable(
                if (source is ConnectionState.Error) "Connections unavailable." else "Connection removed.",
            )
        } else {
            loadJob = load(snapshot)
        }
    }

    fun load(): Job = routeConnection.current()?.let(::load) ?: scope().launch { }

    private fun load(snapshot: RouteConnectionSnapshot): Job {
        routeConnection.publishIfCurrent(snapshot) { _state.value = DecisionUiState.Loading }
        return scope().launch {
            runCatching { repo.getThread(snapshot.connection, threadId) }
                .onSuccess { thread ->
                    routeConnection.publishIfCurrent(snapshot) {
                        val prompt = activePrompt(thread)
                        _state.value = if (prompt == null) {
                            DecisionUiState.NoLongerActionable(thread, "This question is no longer awaiting a response.")
                        } else {
                            DecisionUiState.Content(thread, prompt)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    routeConnection.publishIfCurrent(snapshot) {
                        _state.value = DecisionUiState.Error(error.toKind(), error.message ?: "error")
                    }
                }
        }
    }

    /**
     * Revalidates [Content.prompt] against the latest thread before it posts. A second tap is
     * rejected synchronously by [Content.inFlight], and every UI publication is ownership-fenced.
     */
    fun submit(text: String): Job? {
        if (text.isBlank()) return null
        val snapshot = routeConnection.current() ?: return null
        val content = state.value as? DecisionUiState.Content ?: return null
        if (content.inFlight) return null
        val target = content.prompt
        if (!routeConnection.publishIfCurrent(snapshot) {
                val latest = _state.value as? DecisionUiState.Content ?: return@publishIfCurrent
                if (latest.inFlight || latest.prompt.messageId != target.messageId) return@publishIfCurrent
                _state.value = latest.copy(inFlight = true, sendError = null)
            }) return null

        return scope().launch {
            val refreshed = runCatching { repo.getThread(snapshot.connection, threadId) }
            val latestThread = refreshed.getOrElse { error ->
                if (error is CancellationException) throw error
                routeConnection.publishIfCurrent(snapshot) {
                    val latest = _state.value as? DecisionUiState.Content ?: return@publishIfCurrent
                    if (latest.inFlight && latest.prompt.messageId == target.messageId) {
                        _state.value = latest.copy(inFlight = false, sendError = error.toSendError())
                    }
                }
                return@launch
            }

            val latestPrompt = activePrompt(latestThread)
            if (latestPrompt?.messageId != target.messageId) {
                routeConnection.publishIfCurrent(snapshot) {
                    val latest = _state.value as? DecisionUiState.Content ?: return@publishIfCurrent
                    if (latest.inFlight && latest.prompt.messageId == target.messageId) {
                        _state.value = DecisionUiState.NoLongerActionable(
                            latestThread,
                            "This question changed before your response was sent.",
                        )
                    }
                }
                return@launch
            }

            // The fence makes the preflight result belong to this connection snapshot. The server
            // protocol has no atomic "post only if message id is still current" condition.
            var promptStillOwned = false
            val connectionStillOwned = routeConnection.publishIfCurrent(snapshot) {
                val latest = _state.value as? DecisionUiState.Content
                promptStillOwned = latest != null &&
                    latest.inFlight &&
                    latest.prompt.messageId == target.messageId
            }
            if (!connectionStillOwned || !promptStillOwned) return@launch

            runCatching { repo.postMessage(snapshot.connection, threadId, text) }
                .onSuccess { updated ->
                    routeConnection.publishIfCurrent(snapshot) {
                        val latest = _state.value as? DecisionUiState.Content ?: return@publishIfCurrent
                        if (latest.inFlight && latest.prompt.messageId == target.messageId) {
                            _state.value = DecisionUiState.ResponseRecorded(updated)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    routeConnection.publishIfCurrent(snapshot) {
                        val latest = _state.value as? DecisionUiState.Content ?: return@publishIfCurrent
                        if (latest.inFlight && latest.prompt.messageId == target.messageId) {
                            _state.value = latest.copy(inFlight = false, sendError = error.toSendError())
                        }
                    }
                }
        }.also { submitJob = it }
    }

    private fun activePrompt(thread: Thread): ActiveDecisionPrompt? {
        val unresolved = thread.messages.drop(thread.lastResolvedMessageIndex() + 1)
        unresolved.lastOrNull { it.role == "agent" && it.kind == "decision" && it.decision != null }
            ?.let { return ActiveDecisionPrompt(it.id, it.text, it.decision) }

        // A needs-you question has no typed choices. Use the supplied agent text as-is instead of
        // inventing options, and only expose a concise free-text response in the screen.
        if (!thread.needsYou) return null
        return unresolved.lastOrNull { it.role == "agent" }
            ?.let { ActiveDecisionPrompt(it.id, it.text) }
    }

    private fun Throwable.toKind(): ErrorKind = when (this) {
        is AuthException -> ErrorKind.AUTH
        is NotFoundException -> ErrorKind.NOT_FOUND
        else -> ErrorKind.NETWORK
    }

    private fun Throwable.toSendError(): String = when (this) {
        is AuthException -> "Token rejected — fix this connection in Settings."
        is NotFoundException -> "This question is no longer available."
        else -> "Couldn't send. Check your connection and try again."
    }
}
