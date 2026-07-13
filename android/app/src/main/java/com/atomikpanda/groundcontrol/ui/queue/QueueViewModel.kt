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
        val decisionLoaded: Boolean = true,   // has the focused decision finished loading?
        val actionError: String? = null,      // last inline-action failure (transient), shown as a snackbar
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
    private val deferredKeys = LinkedHashSet<String>()
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

    /** Keep [head] at position 0 (don't yank focus); urgency-sort the active rest of [fresh] behind
     *  it, and keep any deferred cards pinned to the back in their original defer order. */
    private fun mergeKeepingHead(head: QueueCard?, fresh: List<QueueCard>): List<QueueCard> {
        val rest = fresh.filter { it.key != head?.key }
        val (deferred, active) = rest.partition { it.key in deferredKeys }
        val order = deferredKeys.toList()
        return listOfNotNull(head) + sortQueue(active) + deferred.sortedBy { order.indexOf(it.key) }
    }

    /** Approve the current spec card, then advance. On success arms undo; on failure the card
     *  stays put and [Content.actionError] surfaces the failure (no silent dismiss). */
    fun approveCurrent(): Job? {
        val c = content() ?: return null
        val card = c.current ?: return null
        if (card.kind != QueueKind.NEEDS_APPROVAL || card.specId == null) return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            runCatching { repo.approve(conn(card), card.specId) }
                .onSuccess {
                    resolvedKeys.add(card.key); deferredKeys.remove(card.key)
                    advancePast(card, armUndo = true)
                }
                .onFailure {
                    val c2 = content() ?: return@launch
                    _state.value = c2.copy(inFlight = false, actionError = "Couldn't approve — workspace unreachable. Try again.")
                }
        }
    }

    /** Answer the current decision card with the tapped option text, then advance. On success arms
     *  undo; on failure the card stays put and [Content.actionError] surfaces the failure. */
    fun answerDecision(optionText: String): Job? {
        val c = content() ?: return null
        val card = c.current ?: return null
        if (card.kind != QueueKind.NEEDS_DECISION) return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            runCatching { repo.answerDecision(conn(card), card.workItemId, optionText) }
                .onSuccess {
                    resolvedKeys.add(card.key); deferredKeys.remove(card.key)
                    advancePast(card, armUndo = true)
                }
                .onFailure {
                    val c2 = content() ?: return@launch
                    _state.value = c2.copy(inFlight = false, actionError = "Couldn't send — workspace unreachable. Try again.")
                }
        }
    }

    /** Risky cards (blocked / needs_review): screen navigates; we just advance past the head.
     *  Not added to resolvedKeys, so it returns on the next refresh if still pending. */
    fun openCurrent() {
        val c = content() ?: return
        c.current ?: return
        _state.value = c.copy(cards = c.cards.drop(1), resolved = c.resolved + 1, undo = null, actionError = null)
        maybeLoadDecision(content()?.current)
    }

    /** Send the current card to the back of the queue (reorder, never dismiss). */
    fun defer() {
        val c = content() ?: return
        val card = c.current ?: return
        deferredKeys.add(card.key)
        _state.value = c.copy(cards = c.cards.drop(1) + card, undo = null, actionError = null)
        maybeLoadDecision(content()?.current)
    }

    /** Undo the last inline approve/decision: re-insert the card at the head. */
    fun undo() {
        val c = content() ?: return
        val card = c.undo ?: return
        resolvedKeys.remove(card.key)
        _state.value = c.copy(cards = listOf(card) + c.cards, resolved = (c.resolved - 1).coerceAtLeast(0), undo = null, actionError = null)
        maybeLoadDecision(card)
    }

    private fun advancePast(card: QueueCard, armUndo: Boolean) {
        val c = content() ?: return
        // guard: only advance if the head is still this card (no interleaving refresh moved it)
        if (c.current?.key != card.key) { _state.value = c.copy(inFlight = false); return }
        val remaining = c.cards.drop(1)
        _state.value = c.copy(cards = remaining, resolved = c.resolved + 1, undo = if (armUndo) card else null, inFlight = false, actionError = null)
        maybeLoadDecision(remaining.firstOrNull())
    }

    /** When the head is a decision card, fetch its thread's pending decision into state.
     *  Distinguishes "still loading" (`decisionLoaded = false`) from "loaded, no structured
     *  decision" (`decisionLoaded = true, focusedDecision = null`) so the UI can show a spinner
     *  vs. a fallback. Applies the result only if that card is still the head (no stale overwrite). */
    private fun maybeLoadDecision(card: QueueCard?) {
        val c = content() ?: return
        if (card?.kind != QueueKind.NEEDS_DECISION || card.threadId == null) {
            _state.value = c.copy(focusedDecision = null, decisionLoaded = true)
            return
        }
        _state.value = c.copy(focusedDecision = null, decisionLoaded = false)
        scope().launch {
            val prompt = runCatching { repo.loadDecision(conn(card), card.threadId) }.getOrNull()
            val cc = content() ?: return@launch
            if (cc.current?.key == card.key) _state.value = cc.copy(focusedDecision = prompt, decisionLoaded = true)
        }
    }
}
