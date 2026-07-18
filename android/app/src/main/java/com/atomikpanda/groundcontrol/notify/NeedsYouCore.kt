package com.atomikpanda.groundcontrol.notify

import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary

data class NeedsYouEvent(
    val connectionId: String,
    val baseUrl: String,
    val workspaceName: String,
    val threadId: String,
    val subject: String,
    val preview: String,
    val updatedAt: String,
    /** Full thread messages for MessagingStyle context (empty if the enrichment fetch failed). */
    val messages: List<Message> = emptyList(),
    /** The active, still-unanswered decision (drives the option-action buttons), if any. */
    val decision: Decision? = null,
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
    private val repo: ThreadsRepository,
    /** The thread currently open+foregrounded (see [OpenThreadRegistry]), or null. Suppresses a
     *  duplicate notification for the thread the user is already viewing (#378). Defaults to
     *  "nothing open" so non-UI callers/tests keep the original always-notify behavior. */
    private val foregroundThreadKey: () -> String? = { null },
) {
    suspend fun reconcile(conn: WorkspaceConnection, threads: List<ThreadSummary>) {
        for (t in threads) {
            val notified = store.isNotified(conn.id, t.id)
            val needsAttention = t.needsYou || t.needsDecision
            if (needsAttention && !notified) {
                if (shouldSuppressNotification(foregroundThreadKey(), conn.id, t.id)) {
                    // The user is looking at this exact thread right now. Skip the notification and
                    // deliberately do NOT markNotified: if they leave it still-unanswered, a later
                    // reconcile should surface it.
                    continue
                }
                // Fetch the full thread once (gated by the dedupe store, so one GET per new
                // notification) to build MessagingStyle context + resolve the active decision.
                // Degrades to the summary preview if the fetch fails — a notification always fires.
                val messages = runCatching { repo.getThread(conn, t.id).messages }.getOrDefault(emptyList())
                notifier.notify(
                    NeedsYouEvent(
                        connectionId = conn.id,
                        baseUrl = conn.baseUrl,
                        workspaceName = conn.workspaceName,
                        threadId = t.id,
                        subject = t.subject,
                        preview = t.lastMessage,
                        updatedAt = t.updatedAt ?: "",
                        messages = messages,
                        decision = activeDecision(messages),
                    )
                )
                store.markNotified(conn.id, t.id)
            } else if (!needsAttention && notified) {
                store.clear(conn.id, t.id)
            }
        }
    }

    suspend fun fetchAndReconcile(conn: WorkspaceConnection) =
        reconcile(conn, repo.listThreadsFor(conn))
}
