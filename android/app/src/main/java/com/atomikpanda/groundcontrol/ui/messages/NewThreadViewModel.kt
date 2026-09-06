package com.atomikpanda.groundcontrol.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.CaptureDraft
import com.atomikpanda.groundcontrol.data.CaptureDraftPayload
import com.atomikpanda.groundcontrol.data.CaptureDraftStore
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStatePublicationFence
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    /** Capture waits for its scoped draft before it permits an explicit submission. */
    val isDraftLoading: Boolean = false,
    /** Lets the shared screen expose discard only for the persistent capture flow. */
    val isPersistentCapture: Boolean = false,
    val draftRevision: Long = 0,
    val brainstormIdempotencyKey: String? = null,
    val attemptedBrainstorm: CaptureDraftPayload? = null,
)

/** Auto-select only when there is exactly one connection; otherwise require a pick. */
fun defaultSelection(connections: List<WorkspaceConnection>): String? = connections.singleOrNull()?.id

/** True when a thread can be created: text, a selected connection, and a hydrated draft are ready. */
fun canCreate(state: NewThreadUiState): Boolean =
    state.text.isNotBlank() && state.selectedConnectionId != null && !state.inFlight && !state.isDraftLoading

private data class CreateRequest(
    val state: NewThreadUiState,
    val connection: WorkspaceConnection,
    val brainstormPayload: CaptureDraftPayload?,
)

class NewThreadViewModel(
    private val repo: ThreadsRepository,
    private val connectionState: StateFlow<ConnectionState>,
    private val testScope: CoroutineScope? = null,
    private val captureDraftStore: CaptureDraftStore? = null,
    private val captureContextId: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(
        NewThreadUiState(
            isDraftLoading = captureDraftStore != null,
            isPersistentCapture = captureDraftStore != null,
        ),
    )
    val state: StateFlow<NewThreadUiState> = _state.asStateFlow()

    private var selectionInitialized = captureContextId != null
    private var initialSelectionId: String? = captureContextId
    private var hasAppliedReadyConnections = false
    private var nextDraftRevision = 0L
    private var draftHydrationSuperseded = false
    private var pendingDraftDiscard = false

    private fun scope() = testScope ?: viewModelScope

    init {
        if (captureDraftStore != null) hydrateCaptureDraft(captureDraftStore)
        scope().launch {
            connectionState.collectLatest { source ->
                when (source) {
                    ConnectionState.Loading -> _state.value = _state.value.copy(
                        connections = emptyList(),
                        isLoading = true,
                        connectionError = null,
                    )
                    is ConnectionState.Error -> _state.value = _state.value.copy(
                        connections = emptyList(),
                        isLoading = false,
                        connectionError = source.cause,
                    )
                    is ConnectionState.Ready -> applyConnections(source.connections)
                }
            }
        }
    }

    /** Applies a route preselection once; subsequent user choice is never reset by recomposition. */
    fun initializeSelection(initialConnectionId: String?) {
        synchronized(ConnectionStatePublicationFence.lock) {
            if (selectionInitialized) return
            selectionInitialized = true
            initialSelectionId = initialConnectionId
            val connections = _state.value.connections
            val selected = initialConnectionId?.let { connections.findByConnectionId(it)?.id }
            if (selected != null || initialConnectionId != null) {
                _state.value = _state.value.copy(selectedConnectionId = selected)
            }
        }
    }

    fun load() {
        when (val source = connectionState.value) {
            ConnectionState.Loading -> _state.value = _state.value.copy(
                connections = emptyList(), isLoading = true, connectionError = null, message = null,
            )
            is ConnectionState.Error -> _state.value = _state.value.copy(
                connections = emptyList(), isLoading = false, connectionError = source.cause, message = null,
            )
            is ConnectionState.Ready -> {
                applyConnections(source.connections)
                _state.value = _state.value.copy(message = null)
            }
        }
    }

    private fun applyConnections(conns: List<WorkspaceConnection>) {
        synchronized(ConnectionStatePublicationFence.lock) {
            val previous = _state.value
            val selected = previous.selectedConnectionId
                ?.let { conns.findByConnectionId(it) }
                ?.id
                ?: if (!hasAppliedReadyConnections) {
                    initialSelectionId
                        ?.let { conns.findByConnectionId(it)?.id }
                        ?: if (initialSelectionId == null) defaultSelection(conns) else null
                } else {
                    // A removed selection must be chosen again, even when only one connection remains.
                    null
                }
            hasAppliedReadyConnections = true
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
            } else {
                null
            }
            _state.value = previous.copy(
                connections = conns,
                selectedConnectionId = selected,
                isLoading = false,
                connectionError = null,
                message = message,
            )
        }
    }

    private fun hydrateCaptureDraft(store: CaptureDraftStore) {
        scope().launch {
            val storedRevision = store.latestRevision(captureContextId)
            val restored = store.load(captureContextId)
            var save: CaptureDraft? = null
            var clearRevision: Long? = null
            synchronized(ConnectionStatePublicationFence.lock) {
                nextDraftRevision = maxOf(nextDraftRevision, storedRevision, restored?.revision ?: 0L)
                val current = _state.value
                when {
                    pendingDraftDiscard -> {
                        val discardRevision = nextRevision()
                        clearRevision = discardRevision
                        _state.value = current.copy(
                            subject = "",
                            text = "",
                            kind = CaptureKind.QUICK_NOTE,
                            brainstormIdempotencyKey = null,
                            attemptedBrainstorm = null,
                            draftRevision = discardRevision,
                            isDraftLoading = false,
                        )
                    }
                    draftHydrationSuperseded -> {
                        val updated = current.copy(
                            isDraftLoading = false,
                            draftRevision = nextRevision(),
                        )
                        _state.value = updated
                        save = updated.toCaptureDraft()
                    }
                    restored != null -> {
                        val restoredKind = runCatching { CaptureKind.valueOf(restored.kind) }
                            .getOrDefault(CaptureKind.QUICK_NOTE)
                        _state.value = current.copy(
                            subject = restored.subject,
                            text = restored.text,
                            kind = restoredKind,
                            // Route scope selects the destination only for a fresh draft. A saved
                            // draft is already scoped to this route and retains its explicit choice.
                            selectedConnectionId = restored.selectedConnectionId,
                            brainstormIdempotencyKey = restored.brainstormIdempotencyKey,
                            attemptedBrainstorm = restored.attemptedBrainstorm,
                            draftRevision = restored.revision,
                            isDraftLoading = false,
                        )
                    }
                    else -> _state.value = current.copy(isDraftLoading = false)
                }
            }
            clearRevision?.let { store.clear(captureContextId, it) }
            save?.let { store.save(captureContextId, it) }
            (connectionState.value as? ConnectionState.Ready)?.let { applyConnections(it.connections) }
        }
    }

    private fun nextRevision(): Long {
        nextDraftRevision += 1
        return nextDraftRevision
    }

    private fun NewThreadUiState.toCaptureDraft(): CaptureDraft = CaptureDraft(
        subject = subject,
        text = text,
        kind = kind.name,
        selectedConnectionId = selectedConnectionId,
        brainstormIdempotencyKey = brainstormIdempotencyKey,
        attemptedBrainstorm = attemptedBrainstorm,
        revision = draftRevision,
    )

    private fun updateCapture(transform: (NewThreadUiState) -> NewThreadUiState) {
        val store = captureDraftStore
        var save: CaptureDraft? = null
        synchronized(ConnectionStatePublicationFence.lock) {
            val current = _state.value
            var updated = transform(current)
            if (store == null) {
                _state.value = updated
                return
            }
            if (current.isDraftLoading) {
                draftHydrationSuperseded = true
                _state.value = updated
                return
            }
            updated = updated.copy(draftRevision = nextRevision())
            _state.value = updated
            save = updated.toCaptureDraft()
        }
        store?.let { draftStore ->
            save?.let { snapshot -> scope().launch { draftStore.save(captureContextId, snapshot) } }
        }
    }

    private fun NewThreadUiState.withInvalidatedBrainstormAttempt(): NewThreadUiState =
        copy(brainstormIdempotencyKey = null, attemptedBrainstorm = null)

    fun onSubjectChange(subject: String) = updateCapture { current ->
        if (current.subject == subject) current
        else current.copy(subject = subject).withInvalidatedBrainstormAttempt()
    }

    fun onTextChange(text: String) = updateCapture { current ->
        if (current.text == text) current
        else current.copy(text = text).withInvalidatedBrainstormAttempt()
    }

    fun onSelectConnection(id: String) {
        val canonicalId = _state.value.connections.findByConnectionId(id)?.id ?: id
        updateCapture { current ->
            if (current.selectedConnectionId == canonicalId) current
            else current.copy(selectedConnectionId = canonicalId).withInvalidatedBrainstormAttempt()
        }
    }

    fun onSelectKind(kind: CaptureKind) = updateCapture { current ->
        if (current.kind == kind) current
        else current.copy(kind = kind).withInvalidatedBrainstormAttempt()
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    /** Explicitly discards only this route context's draft; ordinary New thread is unchanged. */
    fun discardCaptureDraft() {
        val store = captureDraftStore ?: return
        var expectedRevision: Long? = null
        synchronized(ConnectionStatePublicationFence.lock) {
            val current = _state.value
            if (current.isDraftLoading) {
                pendingDraftDiscard = true
                draftHydrationSuperseded = true
                _state.value = current.copy(
                    subject = "", text = "", kind = CaptureKind.QUICK_NOTE,
                    brainstormIdempotencyKey = null, attemptedBrainstorm = null,
                )
                return
            }
            val discardRevision = nextRevision()
            expectedRevision = discardRevision
            _state.value = current.copy(
                subject = "", text = "", kind = CaptureKind.QUICK_NOTE,
                brainstormIdempotencyKey = null, attemptedBrainstorm = null,
                draftRevision = discardRevision,
            )
        }
        expectedRevision?.let { revision -> scope().launch { store.clear(captureContextId, revision) } }
    }

    fun create(): Job? {
        val request = synchronized(ConnectionStatePublicationFence.lock) {
            val current = _state.value
            if (!canCreate(current)) return null
            val ready = connectionState.value as? ConnectionState.Ready ?: return null
            val selectedConnectionId = current.selectedConnectionId ?: return null
            val connection = ready.connections.findByConnectionId(selectedConnectionId) ?: return null
            val subject = current.subject.trim().ifBlank { null }
            val brainstormPayload = if (current.kind == CaptureKind.BRAINSTORM_SPEC) {
                CaptureDraftPayload(connection.id, subject, current.text.trim())
            } else {
                null
            }
            val idempotencyKey = if (brainstormPayload != null) {
                if (current.attemptedBrainstorm == brainstormPayload) {
                    current.brainstormIdempotencyKey ?: UUID.randomUUID().toString()
                } else {
                    UUID.randomUUID().toString()
                }
            } else {
                null
            }
            val updated = current.copy(
                connections = ready.connections,
                selectedConnectionId = connection.id,
                inFlight = true,
                message = null,
                brainstormIdempotencyKey = idempotencyKey,
                attemptedBrainstorm = brainstormPayload,
                draftRevision = if (captureDraftStore != null) nextRevision() else current.draftRevision,
            )
            _state.value = updated
            CreateRequest(updated, connection, brainstormPayload)
        }
        return scope().launch {
            try {
                captureDraftStore?.save(captureContextId, request.state.toCaptureDraft())
                val latest = (connectionState.value as? ConnectionState.Ready)
                    ?.connections
                    ?.findByConnectionId(request.connection.id)
                check(latest == request.connection) { "Connection changed before create." }
                val thread = when (request.state.kind) {
                    CaptureKind.QUICK_NOTE -> repo.createThread(
                        request.connection,
                        request.state.text.trim(),
                        request.state.subject.trim().ifBlank { null },
                    )
                    CaptureKind.BRAINSTORM_SPEC -> repo.captureBrainstorm(
                        request.connection,
                        request.state.text.trim(),
                        request.state.subject.trim().ifBlank { null },
                        checkNotNull(request.state.brainstormIdempotencyKey),
                    )
                }
                publishSuccessfulCreate(request, thread.id)
            } catch (cancelled: CancellationException) {
                synchronized(ConnectionStatePublicationFence.lock) {
                    _state.value = _state.value.copy(inFlight = false)
                }
                throw cancelled
            } catch (error: Throwable) {
                synchronized(ConnectionStatePublicationFence.lock) {
                    val current = (connectionState.value as? ConnectionState.Ready)
                        ?.connections
                        ?.findByConnectionId(request.connection.id)
                    _state.value = if (current == request.connection) {
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

    private fun publishSuccessfulCreate(request: CreateRequest, threadId: String) {
        val store = captureDraftStore
        var clearRevision: Long? = null
        var save: CaptureDraft? = null
        synchronized(ConnectionStatePublicationFence.lock) {
            val current = _state.value
            val unchangedDraft = current.text == request.state.text &&
                current.subject == request.state.subject &&
                current.kind == request.state.kind &&
                current.selectedConnectionId == request.connection.id &&
                current.attemptedBrainstorm == request.brainstormPayload
            val canonical = (connectionState.value as? ConnectionState.Ready)
                ?.connections
                ?.findByConnectionId(request.connection.id)
            val canNavigate = canonical != null &&
                !(canonical.id == request.connection.id && canonical != request.connection)
            var updated = when {
                store != null && unchangedDraft -> current.copy(
                    inFlight = false,
                    text = "",
                    subject = "",
                    brainstormIdempotencyKey = null,
                    attemptedBrainstorm = null,
                )
                store == null && canNavigate -> current.copy(
                    inFlight = false,
                    text = "",
                    subject = "",
                )
                else -> current.copy(inFlight = false)
            }
            if (canonical != null && !(canonical.id == request.connection.id && canonical != request.connection)) {
                updated = updated.copy(message = NewThreadMessage.Created(canonical.id, threadId))
            }
            _state.value = updated
            if (store != null) {
                if (unchangedDraft) clearRevision = request.state.draftRevision
                else save = updated.toCaptureDraft()
            }
        }
        clearRevision?.let { revision -> scope().launch { store?.clear(captureContextId, revision) } }
        save?.let { snapshot -> scope().launch { store?.save(captureContextId, snapshot) } }
    }

    private fun errorText(t: Throwable): String = when (t) {
        is AuthException -> "Token rejected — fix this connection in Settings."
        else -> "Couldn't reach the workspace — try again."
    }
}
