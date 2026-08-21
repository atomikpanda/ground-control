package com.atomikpanda.groundcontrol.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serializes authoritative connection-state replacement with route-result publication. */
internal object ConnectionStatePublicationFence {
    val lock = Any()
}

sealed interface ConnectionState {
    data object Loading : ConnectionState
    data class Ready(val connections: List<WorkspaceConnection>) : ConnectionState
    data class Error(val cause: Throwable) : ConnectionState
}

interface ConnectionStateSource {
    val state: StateFlow<ConnectionState>
    fun retry()
}

/** Application-lifetime projection of the authoritative DataStore connection records. */
class ConnectionStateStore(
    private val source: () -> Flow<List<WorkspaceConnection>>,
    private val scope: CoroutineScope,
) : ConnectionStateSource {
    constructor(source: Flow<List<WorkspaceConnection>>, scope: CoroutineScope) : this({ source }, scope)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val observationLock = Any()
    private var observation: Job? = null

    init {
        observe()
    }

    override fun retry() = synchronized(observationLock) {
        if (observation?.isActive != true) observeLocked()
    }

    private fun observe() = synchronized(observationLock) { observeLocked() }

    private fun observeLocked() {
        observation = scope.launch {
            try {
                source().collect { connections ->
                    synchronized(ConnectionStatePublicationFence.lock) {
                        _state.value = ConnectionState.Ready(connections)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                synchronized(ConnectionStatePublicationFence.lock) {
                    _state.value = ConnectionState.Error(error)
                }
            }
        }
    }
}
