// app/src/main/java/com/atomikpanda/groundcontrol/data/QueueRepository.kt
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.ui.home.displayName
import com.atomikpanda.groundcontrol.ui.queue.DecisionPrompt
import com.atomikpanda.groundcontrol.ui.queue.QueueCard
import com.atomikpanda.groundcontrol.ui.queue.cardsFrom
import com.atomikpanda.groundcontrol.ui.queue.pendingDecision
import com.atomikpanda.groundcontrol.ui.queue.sortQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The merged cross-workspace Queue: one card per pending action + per-workspace errors. */
data class QueueFeed(val cards: List<QueueCard>, val errors: List<WorkspaceError>)

/**
 * Fans out `GET /items` over every connected workspace, maps each WorkItem's
 * attention overlay to one card per pending action, and merges into one
 * urgency-sorted list. A workspace whose fetch fails contributes a
 * [WorkspaceError] instead of sinking the whole Queue. Also the action seam for
 * card faces (approve / answer-decision) and the lazy decision loader.
 */
class QueueRepository(private val api: SpecApi) {
    suspend fun load(connections: List<WorkspaceConnection>): QueueFeed = coroutineScope {
        val perConn = connections.map { conn -> async { loadOne(conn) } }.awaitAll()
        QueueFeed(
            cards = sortQueue(perConn.flatMap { it.cards }),
            errors = perConn.mapNotNull { it.error },
        )
    }

    private data class ConnResult(val cards: List<QueueCard>, val error: WorkspaceError?)

    private suspend fun loadOne(conn: WorkspaceConnection): ConnResult =
        runCatching { api.listItems(conn) }
            .onFailure { if (it is CancellationException) throw it }
            .fold(
                onSuccess = { ConnResult(cardsFrom(conn, it), null) },
                onFailure = { ConnResult(emptyList(), WorkspaceError(conn.id, conn.displayName())) },
            )

    /** Approve a spec from a needs_approval card (inline safe action). */
    suspend fun approve(conn: WorkspaceConnection, specId: String) =
        api.approve(conn, specId, bypassGate = false)

    /** Answer a decision: append the tapped option's text to the item's thread. */
    suspend fun answerDecision(conn: WorkspaceConnection, itemId: String, text: String) =
        api.postItemMessage(conn, itemId, text)

    /** Load the first pending decision across the card's threads. Null if none. */
    suspend fun loadDecision(conn: WorkspaceConnection, threadIds: List<String>): DecisionPrompt? {
        for (tid in threadIds) {
            val p = runCatching { pendingDecision(api.getThread(conn, tid)) }.getOrNull()
            if (p != null) return p
        }
        return null
    }
}
