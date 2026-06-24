// android/app/src/main/java/com/atomikpanda/groundcontrol/ui/home/NeedsYouItem.kt
package com.atomikpanda.groundcontrol.ui.home

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.SpecSummary
import com.atomikpanda.groundcontrol.data.dto.TaskSummary
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary

/** Sort tiers for the "Needs you" queue. Lower ordinal = higher urgency. */
enum class UrgencyTier { BLOCKER, QUESTION, APPROVAL }

/** One actionable item in the cross-workspace "Needs you" queue. */
sealed interface NeedsYouItem {
    val connectionId: String
    val workspaceName: String
    val tier: UrgencyTier
    /** Recency key within a tier; newest-first == descending sort. */
    val sortKey: String
    /** Stable, unique key for LazyColumn. */
    val key: String

    data class Approval(
        override val connectionId: String,
        override val workspaceName: String,
        val specId: String,
        val title: String,
    ) : NeedsYouItem {
        override val tier get() = UrgencyTier.APPROVAL
        override val sortKey get() = title.lowercase()   // /specs carries no timestamp
        override val key get() = "approval:$connectionId:$specId"
    }

    data class Question(
        override val connectionId: String,
        override val workspaceName: String,
        val threadId: String,
        val subject: String,
        val lastMessage: String,
        val updatedAt: String,
    ) : NeedsYouItem {
        override val tier get() = UrgencyTier.QUESTION
        override val sortKey get() = updatedAt
        override val key get() = "question:$connectionId:$threadId"
    }

    data class Blocker(
        override val connectionId: String,
        override val workspaceName: String,
        val taskSlug: String,
        val reason: String,
        val createdAt: String,
    ) : NeedsYouItem {
        override val tier get() = UrgencyTier.BLOCKER
        override val sortKey get() = createdAt
        override val key get() = "blocker:$connectionId:$taskSlug"
    }
}

/** Display name for a workspace (blank name → baseUrl). */
fun WorkspaceConnection.displayName(): String = workspaceName.ifBlank { baseUrl }

fun approvalsFrom(conn: WorkspaceConnection, specs: List<SpecSummary>): List<NeedsYouItem> =
    specs.filter { it.status == "needs_review" }
        .map { NeedsYouItem.Approval(conn.id, conn.displayName(), it.id, it.title) }

fun questionsFrom(conn: WorkspaceConnection, threads: List<ThreadSummary>): List<NeedsYouItem> =
    threads.filter { it.awaitingReply }
        .map { NeedsYouItem.Question(conn.id, conn.displayName(), it.id, it.subject, it.lastMessage, it.updatedAt ?: "") }

fun blockersFrom(conn: WorkspaceConnection, tasks: List<TaskSummary>): List<NeedsYouItem> =
    tasks.filter { it.blockedReason != null }
        .map { NeedsYouItem.Blocker(conn.id, conn.displayName(), it.slug, it.blockedReason ?: "", it.createdAt ?: "") }

/** Urgency order: tier asc (blocker first), then newest-first within tier. */
val needsYouComparator: Comparator<NeedsYouItem> =
    compareBy<NeedsYouItem> { it.tier.ordinal }.thenByDescending { it.sortKey }

fun sortNeedsYou(items: List<NeedsYouItem>): List<NeedsYouItem> = items.sortedWith(needsYouComparator)
