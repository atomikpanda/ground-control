package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class WorkspaceThreads(
    val connection: WorkspaceConnection,
    val threads: Result<List<ThreadSummary>>,
)

class ThreadsRepository(private val api: SpecApi) {
    suspend fun listAllThreads(connections: List<WorkspaceConnection>): List<WorkspaceThreads> =
        coroutineScope {
            connections.map { conn ->
                async { WorkspaceThreads(conn, runCatching { api.listThreads(conn) }) }
            }.awaitAll()
        }

    suspend fun getThread(conn: WorkspaceConnection, id: String) = api.getThread(conn, id)
    suspend fun createThread(conn: WorkspaceConnection, text: String, subject: String?) = api.createThread(conn, text, subject)
    suspend fun captureBrainstorm(conn: WorkspaceConnection, idea: String, title: String? = null, idempotencyKey: String? = null) =
        api.captureBrainstorm(conn, idea, title, idempotencyKey)
    suspend fun postMessage(conn: WorkspaceConnection, id: String, text: String) = api.postMessage(conn, id, text)
    suspend fun markSeen(conn: WorkspaceConnection, id: String, seenAt: String?) =
        api.markThreadSeen(conn, id, seenAt)
    suspend fun waitForChange(conn: WorkspaceConnection, since: String, timeoutSeconds: Int) =
        api.listThreadsWait(conn, since, timeoutSeconds)
    suspend fun listThreadsFor(conn: WorkspaceConnection) = api.listThreads(conn)

    /** Recent task-journal entries for a thread's linked task (MOS-224 activity strip). */
    suspend fun getJournal(conn: WorkspaceConnection, slug: String): List<JournalEntry> =
        api.getJournal(conn, slug)
}
