package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
import com.atomikpanda.groundcontrol.ui.home.NewMessageNote
import com.atomikpanda.groundcontrol.ui.home.approvalsFrom
import com.atomikpanda.groundcontrol.ui.home.blockersFrom
import com.atomikpanda.groundcontrol.ui.home.decisionsFrom
import com.atomikpanda.groundcontrol.ui.home.displayName
import com.atomikpanda.groundcontrol.ui.home.notesFrom
import com.atomikpanda.groundcontrol.ui.home.questionsFrom
import com.atomikpanda.groundcontrol.ui.home.sortNeedsYou
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A workspace whose fetch failed (one or more sources errored). */
data class WorkspaceError(val connectionId: String, val workspaceName: String)

/** The merged cross-workspace "Needs you" feed. */
data class HomeFeed(
    val items: List<NeedsYouItem>,
    val notes: List<NewMessageNote>,
    val errors: List<WorkspaceError>,
)

/**
 * Fans out over every connected workspace, pulling specs + threads + tasks
 * concurrently, mapping each to "blocked on you" items, and merging into one
 * urgency-sorted list. A workspace whose fetch fails contributes a
 * [WorkspaceError] instead of sinking the whole feed.
 */
class HomeFeedRepository(private val api: SpecApi) {
    suspend fun load(connections: List<WorkspaceConnection>): HomeFeed = coroutineScope {
        val perConn = connections.map { conn -> async { loadOne(conn) } }.awaitAll()
        HomeFeed(
            items = sortNeedsYou(perConn.flatMap { it.items }),
            notes = perConn.flatMap { it.notes }.sortedByDescending { it.updatedAt },
            errors = perConn.mapNotNull { it.error },
        )
    }

    private data class ConnResult(
        val items: List<NeedsYouItem>,
        val notes: List<NewMessageNote>,
        val error: WorkspaceError?,
    )

    /** Like [runCatching], but never swallows structured-concurrency cancellation. */
    private inline fun <T> catchingApi(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    private suspend fun loadOne(conn: WorkspaceConnection): ConnResult = coroutineScope {
        val specs = async { catchingApi { api.listSpecs(conn) } }
        val threads = async { catchingApi { api.listThreads(conn) } }
        val tasks = async { catchingApi { api.listTasks(conn) } }
        val s = specs.await()
        val t = threads.await()
        val k = tasks.await()
        val items = buildList {
            s.getOrNull()?.let { addAll(approvalsFrom(conn, it)) }
            t.getOrNull()?.let { addAll(questionsFrom(conn, it)) }
            t.getOrNull()?.let { addAll(decisionsFrom(conn, it)) }
            k.getOrNull()?.let { addAll(blockersFrom(conn, it)) }
        }
        val notes = t.getOrNull()?.let { notesFrom(conn, it) } ?: emptyList()
        val failed = s.isFailure || t.isFailure || k.isFailure
        ConnResult(items, notes, if (failed) WorkspaceError(conn.id, conn.displayName()) else null)
    }
}
