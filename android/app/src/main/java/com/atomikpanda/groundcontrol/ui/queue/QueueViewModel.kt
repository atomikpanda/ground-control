// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueViewModel.kt
package com.atomikpanda.groundcontrol.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.QueueFeed
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QueueUiState {
    data object Loading : QueueUiState
    data object EmptyConfig : QueueUiState
    data class Content(
        val cards: List<QueueCard>,               // live queue; head = current card
        val resolved: Int,                        // acted-count, for the position indicator
        val focusedDecision: DecisionPrompt?,     // loaded lazily when the head is a decision
        val errors: List<WorkspaceError>,
        val undo: QueueCard?,                      // last acted card, re-insertable at head
        val inFlight: Boolean,
    ) : QueueUiState {
        val current: QueueCard? get() = cards.firstOrNull()
        val total: Int get() = resolved + cards.size
        val position: Int get() = if (cards.isEmpty()) total else resolved + 1
        val caughtUp: Boolean get() = cards.isEmpty()
    }
}

class QueueViewModel(
    private val repo: QueueRepository,
    private val connectionsProvider: () -> List<WorkspaceConnection>,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<QueueUiState>(QueueUiState.Loading)
    val state: StateFlow<QueueUiState> = _state.asStateFlow()

    private val resolvedKeys = mutableSetOf<String>()
    private var connById: Map<String, WorkspaceConnection> = emptyMap()

    private fun scope(): CoroutineScope = testScope ?: viewModelScope
    private fun content(): QueueUiState.Content? = _state.value as? QueueUiState.Content
    private fun conn(card: QueueCard): WorkspaceConnection = connById.getValue(card.connectionId)

    fun refresh(): Job? {
        val connections = connectionsProvider()
        if (connections.isEmpty()) { _state.value = QueueUiState.EmptyConfig; return null }
        connById = connections.associateBy { it.id }
        val prev = content()
        if (prev == null) _state.value = QueueUiState.Loading
        return scope().launch {
            val feed = repo.load(connections)
            val fresh = feed.cards.filterNot { it.key in resolvedKeys }
            if (prev == null) {
                _state.value = QueueUiState.Content(
                    cards = fresh, resolved = 0, focusedDecision = null,
                    errors = feed.errors, undo = null, inFlight = false,
                )
                maybeLoadDecision(fresh.firstOrNull())
            } else {
                // live refresh: keep the current head stable, merge the rest by urgency
                val head = prev.current
                val merged = mergeKeepingHead(head, fresh)
                _state.value = prev.copy(cards = merged, errors = feed.errors)
            }
        }
    }

    /** Keep [head] at position 0 (don't yank focus); urgency-sort the rest of [fresh] behind it. */
    private fun mergeKeepingHead(head: QueueCard?, fresh: List<QueueCard>): List<QueueCard> =
        listOfNotNull(head) + sortQueue(fresh.filter { it.key != head?.key })

    // --- decision loading, act/defer/undo added in Tasks 4 & 5 ---
    private fun maybeLoadDecision(card: QueueCard?) { /* Task 5 */ }
}
