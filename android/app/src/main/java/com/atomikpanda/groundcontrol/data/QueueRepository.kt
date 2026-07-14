// app/src/main/java/com/atomikpanda/groundcontrol/data/QueueRepository.kt
package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.SpecReview
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.home.displayName
import com.atomikpanda.groundcontrol.ui.queue.QueueV2Card
import com.atomikpanda.groundcontrol.ui.queue.cardsFromSpec
import com.atomikpanda.groundcontrol.ui.queue.decisionCardFrom
import com.atomikpanda.groundcontrol.ui.queue.sortQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The merged cross-workspace Queue: one card per review chunk / open decision + per-workspace errors. */
data class QueueFeed(val cards: List<QueueV2Card>, val errors: List<WorkspaceError>)

/**
 * Sources the Queue v2 feed across every connected workspace. Per workspace it
 * fans out two lookups: the `needs_review` specs (each fetched in full and
 * exploded into prose/criteria/questions chunk cards) and the threads with an
 * open decision (each fetched and turned into a decision card). The per-workspace
 * results merge into one urgency-sorted list; a workspace whose fetch fails
 * contributes a [WorkspaceError] instead of sinking the whole Queue. Also the
 * action seam for card faces — every mutation is a thin `api.*` passthrough.
 */
class QueueRepository(private val api: SpecApi) {
    suspend fun load(connections: List<WorkspaceConnection>): QueueFeed = coroutineScope {
        val perConn = connections.map { conn -> async { loadOne(conn) } }.awaitAll()
        QueueFeed(
            cards = sortQueue(perConn.flatMap { it.cards }),
            errors = perConn.mapNotNull { it.error },
        )
    }

    private data class ConnResult(val cards: List<QueueV2Card>, val error: WorkspaceError?)

    private suspend fun loadOne(conn: WorkspaceConnection): ConnResult =
        runCatching { sourceCards(conn) }
            .onFailure { if (it is CancellationException) throw it }
            .fold(
                onSuccess = { ConnResult(it, null) },
                onFailure = { ConnResult(emptyList(), WorkspaceError(conn.id, conn.displayName())) },
            )

    /** One workspace's cards: `needs_review` spec chunks + open thread decisions.
     *  Any failure here (list or per-item fetch) propagates so [loadOne] can isolate
     *  the whole workspace to a [WorkspaceError] rather than emit a partial queue. */
    private suspend fun sourceCards(conn: WorkspaceConnection): List<QueueV2Card> = coroutineScope {
        val specCards = async {
            api.listSpecs(conn)
                .filter { it.status == "needs_review" }
                .flatMap { summary -> cardsFromSpec(conn, api.getSpec(conn, summary.id)) }
        }
        val decisionCards = async {
            api.listThreads(conn)
                .filter { it.needsDecision }
                .mapNotNull { summary -> decisionCardFrom(conn, api.getThread(conn, summary.id)) }
        }
        specCards.await() + decisionCards.await()
    }

    // --- action seams (thin api.* passthroughs for the card faces) ---

    /** Set one prose section's verdict, optionally with a flag comment (MOS-172/217). */
    suspend fun setProseVerdict(conn: WorkspaceConnection, specId: String, sectionId: String, verdict: String, comment: String? = null): SpecReview =
        api.setProseVerdict(conn, specId, sectionId, verdict, comment)

    /** Set one acceptance criterion's verdict, optionally with a flag comment (MOS-217). */
    suspend fun setCriterionVerdict(conn: WorkspaceConnection, specId: String, criterionId: String, verdict: String, comment: String? = null): SpecReview =
        api.setVerdict(conn, specId, criterionId, verdict, comment)

    /** Answer one of a spec's open questions. */
    suspend fun answerQuestion(conn: WorkspaceConnection, specId: String, questionId: String, answer: String): SpecReview =
        api.answerQuestion(conn, specId, questionId, answer)

    /** Approve a spec (gated). Surfaces the server's 409 blockers to the caller. */
    suspend fun approve(conn: WorkspaceConnection, specId: String): SpecReview =
        api.approve(conn, specId, bypassGate = false)

    /** Request changes on a spec, recording the reviewer's reason. */
    suspend fun requestChanges(conn: WorkspaceConnection, specId: String, reason: String): SpecReview =
        api.requestChanges(conn, specId, reason)

    /** Answer a decision: append the chosen option's text to the decision's thread. */
    suspend fun answerDecision(conn: WorkspaceConnection, threadId: String, text: String): Thread =
        api.postMessage(conn, threadId, text)
}
