// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueViewModel.kt
package com.atomikpanda.groundcontrol.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ApiConflictException
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.data.dto.SpecReview
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
        val cards: List<QueueV2Card>,             // live queue; head = current card
        val resolved: Int,                        // acted-count, for the position indicator
        val errors: List<WorkspaceError>,
        val undo: QueueV2Card?,                    // last acted card, re-insertable at head
        val inFlight: Boolean,
        val actionError: String? = null,          // last inline-action failure (transient), shown as a snackbar
    ) : QueueUiState {
        val current: QueueV2Card? get() = cards.firstOrNull()
        val total: Int get() = resolved + cards.size
        val position: Int get() = if (cards.isEmpty()) total else resolved + 1
        val caughtUp: Boolean get() = cards.isEmpty()
    }
}

/**
 * Queue v2 head-stable card stack. PR2 wired the sourcing (specs+threads → cards)
 * and the generic stack machinery (head-stable refresh / defer-to-back / undo /
 * position). PR3 (this file) lands the full v2 transitions on top of that machinery:
 *
 *  - [approveAllCurrent] — approve every item on the head Prose/Criteria card, then
 *    auto-approve the spec when it's fully approvable (409 "cannot approve" ⇒ the
 *    spec's other chunks are still un-approved, so we advance past the head only and
 *    leave them in the queue).
 *  - [rejectCurrent] — flag the head card's item(s) + request changes on the spec;
 *    on success every one of that spec's cards leaves the queue.
 *  - [setItemVerdict] / [answerQuestion] — per-item edits inside a multi-item card,
 *    applied in place (no advance).
 *  - [answerDecision] — post the chosen option to the thread and advance (from PR2).
 *  - [skip] — send the head to the back (the PR2 `defer`).
 */
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
    private fun conn(card: QueueV2Card): WorkspaceConnection = connById.getValue(card.connectionId)

    /** The spec a chunk card belongs to (null for a decision card). */
    private fun QueueV2Card.specId(): String? = when (this) {
        is ProseCard -> specId
        is CriteriaCard -> specId
        is QuestionsCard -> specId
        is DecisionCard -> null
    }

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
                    cards = fresh, resolved = 0,
                    errors = feed.errors, undo = null, inFlight = false,
                )
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
    private fun mergeKeepingHead(head: QueueV2Card?, fresh: List<QueueV2Card>): List<QueueV2Card> {
        val rest = fresh.filter { it.key != head?.key }
        val (deferred, active) = rest.partition { it.key in deferredKeys }
        val order = deferredKeys.toList()
        return listOfNotNull(head) + sortQueue(active) + deferred.sortedBy { order.indexOf(it.key) }
    }

    // --- v2 transitions -----------------------------------------------------

    /**
     * Approve every item on the head Prose/Criteria card, then auto-approve the spec.
     * CriteriaCard → `setCriterionVerdict(approved)` per item; ProseCard →
     * `setProseVerdict(approved)`. Then attempt `approve(bypass=false)`:
     *  - success ⇒ the spec is fully approved: every one of its cards leaves the queue.
     *  - 409 "cannot approve" ⇒ other chunks are still un-approved: advance past the
     *    head only, its siblings stay in the queue (they'll be reviewed in turn).
     *  - any other failure ⇒ the card stays put and [Content.actionError] surfaces it.
     * Approve-all does not apply to Questions (answer, not approve) or Decision cards.
     */
    fun approveAllCurrent(): Job? {
        val c = content() ?: return null
        val card = c.current ?: return null
        if (card !is ProseCard && card !is CriteriaCard) return null
        val specId = card.specId() ?: return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            val conn = conn(card)
            runCatching {
                when (card) {
                    is CriteriaCard -> card.items.forEach { repo.setCriterionVerdict(conn, specId, it.id, "approved") }
                    is ProseCard -> repo.setProseVerdict(conn, specId, card.sectionId, "approved")
                    else -> {}
                }
                repo.approve(conn, specId)          // auto-approve; 409 when other chunks remain
            }.onSuccess {
                // whole spec approved — all its cards leave the queue
                resolveSpec(specId)
                removeSpecCardsAdvancing(specId, card, armUndo = true)
            }.onFailure { t ->
                if (t is ApiConflictException && t.detail.contains("cannot approve")) {
                    // other chunks still un-approved: this card's items ARE approved, so advance past it only
                    resolvedKeys.add(card.key); deferredKeys.remove(card.key)
                    advancePast(card, armUndo = true)
                } else {
                    val c2 = content() ?: return@launch
                    _state.value = c2.copy(inFlight = false, actionError = "Couldn't approve — try again.")
                }
            }
        }
    }

    /**
     * Flag the head card's item(s) with [comment] and request changes on its spec.
     * On success every one of that spec's cards leaves the queue (its chunks are no
     * longer under review). On failure the card stays put and surfaces [actionError].
     */
    fun rejectCurrent(comment: String): Job? {
        val c = content() ?: return null
        val card = c.current ?: return null
        val specId = card.specId() ?: return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            val conn = conn(card)
            runCatching {
                when (card) {
                    is CriteriaCard -> card.items.forEach { repo.setCriterionVerdict(conn, specId, it.id, "flagged", comment) }
                    is ProseCard -> repo.setProseVerdict(conn, specId, card.sectionId, "flagged", comment)
                    else -> {}
                }
                repo.requestChanges(conn, specId, comment)
            }.onSuccess {
                resolveSpec(specId)
                removeSpecCardsAdvancing(specId, card, armUndo = false)
            }.onFailure {
                val c2 = content() ?: return@launch
                _state.value = c2.copy(inFlight = false, actionError = "Couldn't request changes — try again.")
            }
        }
    }

    /** Per-item approve/flag inside a multi-item CriteriaCard: update the card in place, no advance. */
    fun setItemVerdict(specId: String, itemId: String, verdict: String, comment: String? = null): Job? {
        val c = content() ?: return null
        val card = c.cards.filterIsInstance<CriteriaCard>().firstOrNull { it.specId == specId } ?: return null
        val conn = connById[card.connectionId] ?: return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            runCatching { repo.setCriterionVerdict(conn, specId, itemId, verdict, comment) }
                .onSuccess { rev -> updateCriteriaCard(specId, rev) }
                .onFailure {
                    val c2 = content() ?: return@launch
                    _state.value = c2.copy(inFlight = false, actionError = "Couldn't update — try again.")
                }
        }
    }

    /** Per-item answer inside a QuestionsCard: update the card in place, no advance. */
    fun answerQuestion(specId: String, questionId: String, answer: String): Job? {
        val c = content() ?: return null
        val card = c.cards.filterIsInstance<QuestionsCard>().firstOrNull { it.specId == specId } ?: return null
        val conn = connById[card.connectionId] ?: return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            runCatching { repo.answerQuestion(conn, specId, questionId, answer) }
                .onSuccess { rev -> updateQuestionsCard(specId, rev) }
                .onFailure {
                    val c2 = content() ?: return@launch
                    _state.value = c2.copy(inFlight = false, actionError = "Couldn't answer — try again.")
                }
        }
    }

    /** Answer the current decision card with the tapped option text, then advance. On success arms
     *  undo; on failure the card stays put and [Content.actionError] surfaces the failure. */
    fun answerDecision(optionText: String): Job? {
        val c = content() ?: return null
        val card = c.current as? DecisionCard ?: return null
        _state.value = c.copy(inFlight = true, actionError = null)
        return scope().launch {
            runCatching { repo.answerDecision(conn(card), card.threadId, optionText) }
                .onSuccess {
                    resolvedKeys.add(card.key); deferredKeys.remove(card.key)
                    advancePast(card, armUndo = true)
                }
                .onFailure {
                    val c2 = content() ?: return@launch
                    _state.value = c2.copy(inFlight = false, actionError = "Couldn't send — try again.")
                }
        }
    }

    /** Send the current card to the back of the queue (reorder, never dismiss). */
    fun skip() = defer()

    /** Send the current card to the back of the queue (reorder, never dismiss). */
    fun defer() {
        val c = content() ?: return
        val card = c.current ?: return
        deferredKeys.add(card.key)
        _state.value = c.copy(cards = c.cards.drop(1) + card, undo = null, actionError = null)
    }

    /** Undo the last inline approve/decision: re-insert the card at the head. */
    fun undo() {
        val c = content() ?: return
        val card = c.undo ?: return
        resolvedKeys.remove(card.key)
        _state.value = c.copy(cards = listOf(card) + c.cards, resolved = (c.resolved - 1).coerceAtLeast(0), undo = null, actionError = null)
    }

    // --- helpers ------------------------------------------------------------

    /** Mark every currently-queued card of [specId] resolved (so it stays gone across refreshes). */
    private fun resolveSpec(specId: String) {
        val keys = content()?.cards?.filter { it.specId() == specId }?.map { it.key }.orEmpty()
        resolvedKeys.addAll(keys); deferredKeys.removeAll(keys.toSet())
    }

    /** Drop every card of [specId] from the live queue in one step (advancing past a resolved spec).
     *  Guarded on the head still being [head] so an interleaving refresh can't advance the wrong card. */
    private fun removeSpecCardsAdvancing(specId: String, head: QueueV2Card, armUndo: Boolean) {
        val c = content() ?: return
        if (c.current?.key != head.key) { _state.value = c.copy(inFlight = false); return }
        val remaining = c.cards.filterNot { it.specId() == specId }
        val removed = c.cards.size - remaining.size
        _state.value = c.copy(
            cards = remaining,
            resolved = c.resolved + removed,
            undo = if (armUndo) head else null,
            inFlight = false,
            actionError = null,
        )
    }

    /** Advance past the head [card] (drop it), arming undo when asked. Guarded on the head not
     *  having moved under an interleaving refresh. */
    private fun advancePast(card: QueueV2Card, armUndo: Boolean) {
        val c = content() ?: return
        if (c.current?.key != card.key) { _state.value = c.copy(inFlight = false); return }
        val remaining = c.cards.drop(1)
        _state.value = c.copy(cards = remaining, resolved = c.resolved + 1, undo = if (armUndo) card else null, inFlight = false, actionError = null)
    }

    /** Rebuild the queued CriteriaCard for [specId] from a write's returned review. */
    private fun updateCriteriaCard(specId: String, rev: SpecReview) {
        val c = content() ?: return
        val cards = c.cards.map { card ->
            if (card is CriteriaCard && card.specId == specId)
                card.copy(items = rev.acceptanceCriteria.map { CriterionItem(it.id, it.text, it.verdict, it.comment) })
            else card
        }
        _state.value = c.copy(cards = cards, inFlight = false, actionError = null)
    }

    /** Rebuild the queued QuestionsCard for [specId] from a write's returned review (keeps every
     *  question so a just-entered answer stays visible in its field). */
    private fun updateQuestionsCard(specId: String, rev: SpecReview) {
        val c = content() ?: return
        val cards = c.cards.map { card ->
            if (card is QuestionsCard && card.specId == specId)
                card.copy(items = rev.openQuestions.map { QuestionItem(it.id, it.text, it.answer) })
            else card
        }
        _state.value = c.copy(cards = cards, inFlight = false, actionError = null)
    }
}
