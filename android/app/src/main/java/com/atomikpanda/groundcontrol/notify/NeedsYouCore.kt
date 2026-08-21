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
    /** Production supplies generation-safe Room publication; existing non-Android consumers retain
     * the plain notifier seam. */
    private val publish: suspend (NeedsYouEvent) -> Boolean = { notifier.notify(it); true },
    private val retire: suspend (String, String, String) -> Unit = { _, _, _ -> },
    private val adopt: suspend (String, String, String) -> Unit = { _, _, _ -> },
    /** The thread currently open+foregrounded (see [OpenThreadRegistry]), or null. Suppresses a
     *  duplicate notification for the thread the user is already viewing (#378). Defaults to
     *  "nothing open" so non-UI callers/tests keep the original always-notify behavior. */
    private val foregroundThreadKey: () -> String? = { null },
) {
    suspend fun reconcile(conn: WorkspaceConnection, threads: List<ThreadSummary>) {
        for (t in threads) {
            val needsAttention = t.needsYou || t.needsDecision
            val currentNotified = store.isNotified(conn.id, t.id)
            var retiredNotified = false
            for (retiredId in conn.legacyConnectionIds) {
                if (store.isNotified(retiredId, t.id)) {
                    retiredNotified = true
                    break
                }
            }
            if (retiredNotified) {
                if (needsAttention) {
                    // Persist the current identity before retiring aliases so an interrupted
                    // adoption cannot turn an existing notification into a duplicate.
                    if (!currentNotified) store.markNotified(conn.id, t.id)
                    for (retiredId in conn.legacyConnectionIds) {
                        adopt(retiredId, conn.id, t.id)
                        store.clear(retiredId, t.id)
                    }
                } else {
                    // A resolved alias must be retired, not adopted: otherwise its capability
                    // remains actionable under the canonical identity after dedupe is cleared.
                    for (retiredId in conn.legacyConnectionIds) {
                        retire(retiredId, t.id, t.updatedAt.orEmpty())
                        store.clear(retiredId, t.id)
                    }
                }
            }
            if (needsAttention && !currentNotified && !retiredNotified) {
                if (shouldSuppressNotification(foregroundThreadKey(), conn.id, t.id)) {
                    // The user is looking at this exact thread right now. Skip the notification and
                    // deliberately do NOT markNotified: if they leave it still-unanswered, a later
                    // reconcile should surface it.
                    continue
                }
                // Fetch the full thread once (gated by the dedupe store, so one GET per new
                // notification) to build MessagingStyle context + resolve the active decision.
                // Degrades to the summary preview if the fetch fails — a notification always fires.
                val messages = runCatching { repo.getThread(conn, t.id).messages }
                    .getOrDefault(emptyList())
                val published = publish(
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
                    ),
                )
                if (published) store.markNotified(conn.id, t.id)
            } else if (!needsAttention) {
                // Publication can succeed immediately before a process death prevents the dedupe
                // write; capability retirement, not the dedupe bit, is authoritative on resolve.
                retire(conn.id, t.id, t.updatedAt.orEmpty())
                store.clear(conn.id, t.id)
            }
        }
    }

    suspend fun fetchAndReconcile(conn: WorkspaceConnection) =
        reconcile(conn, repo.listThreadsFor(conn))
}
