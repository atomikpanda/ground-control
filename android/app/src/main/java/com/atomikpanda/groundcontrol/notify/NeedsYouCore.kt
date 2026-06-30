package com.atomikpanda.groundcontrol.notify

import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary

data class NeedsYouEvent(
    val connectionId: String,
    val baseUrl: String,
    val workspaceName: String,
    val threadId: String,
    val subject: String,
    val preview: String,
    val updatedAt: String,
)

interface NotifiedStore {
    suspend fun isNotified(connId: String, threadId: String): Boolean
    suspend fun markNotified(connId: String, threadId: String)
    suspend fun clear(connId: String, threadId: String)
}

interface Notifier {
    fun notify(event: NeedsYouEvent)
}

class NeedsYouReconciler(
    private val store: NotifiedStore,
    private val notifier: Notifier,
) {
    suspend fun reconcile(conn: WorkspaceConnection, threads: List<ThreadSummary>) {
        for (t in threads) {
            val notified = store.isNotified(conn.id, t.id)
            if (t.needsYou && !notified) {
                notifier.notify(
                    NeedsYouEvent(conn.id, conn.baseUrl, conn.workspaceName, t.id, t.subject, t.lastMessage, t.updatedAt ?: "")
                )
                store.markNotified(conn.id, t.id)
            } else if (!t.needsYou && notified) {
                store.clear(conn.id, t.id)
            }
        }
    }

    suspend fun fetchAndReconcile(conn: WorkspaceConnection, repo: ThreadsRepository) {
        reconcile(conn, repo.listThreadsFor(conn))
    }
}
