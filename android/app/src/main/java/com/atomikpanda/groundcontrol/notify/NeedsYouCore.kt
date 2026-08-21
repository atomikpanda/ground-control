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

enum class ReplyCapabilityAdoption {
    ADOPTED,
    RETIRED_WITHOUT_REPLACEMENT,
    RETRY,
}

class NeedsYouReconciler(
    private val store: NotifiedStore,
    private val notifier: Notifier,
    private val repo: ThreadsRepository,
    /** Production supplies generation-safe Room publication; existing non-Android consumers retain
     * the plain notifier seam. */
    private val publish: suspend (NeedsYouEvent) -> Boolean = { notifier.notify(it); true },
    private val retire: suspend (String, String, String) -> Boolean = { _, _, _ -> true },
    private val adopt: suspend (String, String, NeedsYouEvent) -> ReplyCapabilityAdoption = { _, _, _ -> ReplyCapabilityAdoption.ADOPTED },
    /** The thread currently open+foregrounded (see [OpenThreadRegistry]), or null. Suppresses a
     *  duplicate notification for the thread the user is already viewing (#378). Defaults to
     *  "nothing open" so non-UI callers/tests keep the original always-notify behavior. */
    private val foregroundThreadKey: () -> String? = { null },
    /** Reply capabilities are authoritative when publication completed before the dedupe mark.
     *  Defaults to active so consumers without capability tracking keep pure mark-based dedupe;
     *  a false answer republishes a still-needy deduped thread whose notification was retired. */
    private val hasActiveCapability: suspend (String, String) -> Boolean = { _, _ -> true },
) {
    suspend fun reconcile(conn: WorkspaceConnection, threads: List<ThreadSummary>) {
        for (t in threads) {
            val needsAttention = t.needsYou || t.needsDecision
            val currentNotified = store.isNotified(conn.id, t.id)
            suspend fun hasRetiredCapability(retiredId: String): Boolean =
                store.isNotified(retiredId, t.id) || hasActiveCapability(retiredId, t.id)

            var retiredNotified = false
            for (retiredId in conn.legacyConnectionIds) {
                if (hasRetiredCapability(retiredId)) {
                    retiredNotified = true
                    break
                }
            }
            suspend fun eventForThread(): NeedsYouEvent {
                val messages = runCatching { repo.getThread(conn, t.id).messages }
                    .getOrDefault(emptyList())
                return NeedsYouEvent(
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
            }
            if (retiredNotified) {
                if (needsAttention) {
                    var adopted = true
                    var requiresReplacement = false
                    for (retiredId in conn.legacyConnectionIds) {
                        if (hasRetiredCapability(retiredId)) {
                            when (adopt(retiredId, conn.id, eventForThread())) {
                                ReplyCapabilityAdoption.ADOPTED -> store.clear(retiredId, t.id)
                                ReplyCapabilityAdoption.RETIRED_WITHOUT_REPLACEMENT -> {
                                    store.clear(retiredId, t.id)
                                    requiresReplacement = true
                                }
                                ReplyCapabilityAdoption.RETRY -> adopted = false
                            }
                        }
                    }
                    when {
                        !adopted -> Unit
                        requiresReplacement && publish(eventForThread()) -> store.markNotified(conn.id, t.id)
                        !requiresReplacement -> store.markNotified(conn.id, t.id)
                    }
                } else {
                    // A resolved alias must be retired, not adopted: otherwise its capability
                    // remains actionable under the canonical identity after dedupe is cleared.
                    for (retiredId in conn.legacyConnectionIds) {
                        if (
                            retire(retiredId, t.id, t.updatedAt.orEmpty()) ||
                            !hasActiveCapability(retiredId, t.id)
                        ) {
                            store.clear(retiredId, t.id)
                        }
                    }
                }
            }
            if (needsAttention && !retiredNotified && (!currentNotified || !hasActiveCapability(conn.id, t.id))) {
                if (shouldSuppressNotification(foregroundThreadKey(), conn.id, t.id)) {
                    // The user is looking at this exact thread right now. Skip the notification and
                    // deliberately do NOT markNotified: if they leave it still-unanswered, a later
                    // reconcile should surface it.
                    continue
                }
                val published = publish(eventForThread())
                if (published) store.markNotified(conn.id, t.id)
            } else if (!needsAttention) {
                // Publication can succeed immediately before a process death prevents the dedupe
                // write; capability retirement, not the dedupe bit, is authoritative on resolve.
                if (
                    retire(conn.id, t.id, t.updatedAt.orEmpty()) ||
                    !hasActiveCapability(conn.id, t.id)
                ) {
                    store.clear(conn.id, t.id)
                }
            }
        }
    }

    suspend fun fetchAndReconcile(conn: WorkspaceConnection) =
        reconcile(conn, repo.listThreadsFor(conn))
}
