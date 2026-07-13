// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueCard.kt
package com.atomikpanda.groundcontrol.ui.queue

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.home.displayName

/** Urgency tiers for the Queue. Lower ordinal = higher urgency. */
enum class QueueTier { URGENT, APPROVAL, REVIEW }

/** The four pending-action kinds a WorkItem's attention overlay can surface. */
enum class QueueKind(val tier: QueueTier) {
    BLOCKED(QueueTier.URGENT),
    NEEDS_DECISION(QueueTier.URGENT),
    NEEDS_APPROVAL(QueueTier.APPROVAL),
    NEEDS_REVIEW(QueueTier.REVIEW),
}

/** One pending action, one card. Identity = (connectionId, workItemId, kind). */
data class QueueCard(
    val connectionId: String,
    val workspaceName: String,
    val workItemId: String,
    val kind: QueueKind,
    val title: String,
    val specId: String? = null,     // NEEDS_APPROVAL: the spec to approve
    val threadIds: List<String> = emptyList(), // NEEDS_DECISION: threads that may carry the decision
    val prUrl: String? = null,      // NEEDS_REVIEW: the PR to open (github external link)
    val blockedTasks: Int = 0,      // BLOCKED: how many tasks are blocked
    val waitingSince: String = "",  // updatedAt proxy; ascending == oldest-waiting first
) {
    val tier: QueueTier get() = kind.tier
    /** Stable, unique key for dedupe + LazyColumn. */
    val key: String get() = "${kind.name}:$connectionId:$workItemId"
}

/** The pending decision extracted from a thread, for inline rendering. */
data class DecisionPrompt(val text: String, val decision: Decision)

/** Map every set attention flag on each item to one [QueueCard]. */
fun cardsFrom(conn: WorkspaceConnection, items: List<WorkItemSummary>): List<QueueCard> =
    items.flatMap { wi ->
        val ws = conn.displayName()
        val since = wi.updatedAt ?: ""
        buildList {
            if (wi.attention.blocked) add(
                QueueCard(conn.id, ws, wi.id, QueueKind.BLOCKED, wi.title,
                    blockedTasks = wi.attention.blockedTasks, waitingSince = since))
            if (wi.attention.needsDecision) add(
                QueueCard(conn.id, ws, wi.id, QueueKind.NEEDS_DECISION, wi.title,
                    threadIds = wi.threadIds, waitingSince = since))
            if (wi.attention.needsApproval) add(
                QueueCard(conn.id, ws, wi.id, QueueKind.NEEDS_APPROVAL, wi.title,
                    specId = wi.specId, waitingSince = since))
            if (wi.attention.needsReview) add(
                QueueCard(conn.id, ws, wi.id, QueueKind.NEEDS_REVIEW, wi.title,
                    prUrl = wi.externalLinks.firstOrNull { it.provider == "github" && it.url.isWebUrl() }?.url,
                    waitingSince = since))
        }
    }

/** Only web URLs are safe to hand to an external browser open — never intent:/file:/etc. */
private fun String.isWebUrl(): Boolean = startsWith("https://") || startsWith("http://")

/** The latest message carrying a decision is the current prompt (attention gates existence). */
fun pendingDecision(thread: Thread): DecisionPrompt? =
    thread.messages.lastOrNull { it.decision != null }
        ?.let { DecisionPrompt(it.text, it.decision!!) }

/** Urgency order: tier asc (URGENT first), then oldest-waiting first; unknown timestamps last. */
internal val queueComparator: Comparator<QueueCard> =
    compareBy<QueueCard> { it.tier.ordinal }.thenBy { it.waitingSince.ifBlank { "￿" } }

fun sortQueue(cards: List<QueueCard>): List<QueueCard> = cards.sortedWith(queueComparator)
