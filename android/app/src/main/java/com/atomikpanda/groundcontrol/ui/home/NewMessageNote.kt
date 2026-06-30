package com.atomikpanda.groundcontrol.ui.home

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary

/** A quiet "new message" entry on Home: an unread plain agent note (not an action item). */
data class NewMessageNote(
    val connectionId: String,
    val workspaceName: String,
    val threadId: String,
    val subject: String,
    val lastMessage: String,
    val updatedAt: String,
)

// Not sorted here: HomeFeedRepository re-sorts the merged cross-workspace list by updatedAt,
// so a per-connection sort would just be a wasted pass.
fun notesFrom(conn: WorkspaceConnection, threads: List<ThreadSummary>): List<NewMessageNote> =
    threads.filter { it.unseen && !it.needsYou }
        .map { NewMessageNote(conn.id, conn.displayName(), it.id, it.subject, it.lastMessage, it.updatedAt ?: "") }
