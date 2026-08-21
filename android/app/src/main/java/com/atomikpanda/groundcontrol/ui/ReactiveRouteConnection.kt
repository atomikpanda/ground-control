package com.atomikpanda.groundcontrol.ui

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStatePublicationFence
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
data class RouteConnectionSnapshot(
    val connection: WorkspaceConnection,
    internal val generation: Long,
)

/** Keeps a route's stable connection id bound to the latest complete ready snapshot. */
class ReactiveRouteConnection(
    private val connectionId: String,
    private val connectionState: StateFlow<ConnectionState>,
    scope: CoroutineScope,
    private val onState: suspend (ConnectionState, RouteConnectionSnapshot?) -> Unit,
) {
    private var generation = 0L
    private var snapshot: RouteConnectionSnapshot? = null
    private val observedInitialState = connectionState.value
    private val _resolvedConnectionId = MutableStateFlow<String?>(null)
    val resolvedConnectionId: StateFlow<String?> = _resolvedConnectionId.asStateFlow()

    init {
        snapshot = snapshotFor(observedInitialState)
        _resolvedConnectionId.value = snapshot?.connection?.id
        scope.launch {
            var firstEmission = true
            connectionState.collectLatest { state ->
                val unchangedInitialEmission = firstEmission && state == observedInitialState
                firstEmission = false
                if (unchangedInitialEmission) return@collectLatest
                snapshot = snapshotFor(state)
                _resolvedConnectionId.value = snapshot?.connection?.id
                onState(state, snapshot)
            }
        }
    }

    private fun snapshotFor(state: ConnectionState): RouteConnectionSnapshot? = synchronized(this) {
        val connection = (state as? ConnectionState.Ready)
            ?.connections
            ?.findByConnectionId(connectionId)
        if (snapshot?.connection != connection) generation++
        connection?.let { RouteConnectionSnapshot(it, generation) }
    }

    /** Resolve from StateFlow synchronously so a user action cannot post through the stale
     * snapshot in the interval before the collector observes a replacement. */
    fun current(): RouteConnectionSnapshot? = synchronized(this) {
        snapshot = snapshotFor(connectionState.value)
        _resolvedConnectionId.value = snapshot?.connection?.id
        snapshot
    }

    fun isCurrent(candidate: RouteConnectionSnapshot): Boolean = synchronized(this) {
        snapshot = snapshotFor(connectionState.value)
        _resolvedConnectionId.value = snapshot?.connection?.id
        snapshot?.generation == candidate.generation && snapshot?.connection == candidate.connection
    }

    /**
     * Runs a synchronous UI-state publication only while [candidate] remains the authoritative
     * route. ConnectionStateStore publishes replacements through the same fence, so a replacement
     * cannot land between this check and [publish].
     */
    fun publishIfCurrent(candidate: RouteConnectionSnapshot, publish: () -> Unit): Boolean =
        synchronized(ConnectionStatePublicationFence.lock) {
            synchronized(this) {
                snapshot = snapshotFor(connectionState.value)
                if (snapshot?.generation != candidate.generation || snapshot?.connection != candidate.connection) {
                    false
                } else {
                    publish()
                    true
                }
            }
        }
}
