package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.ui.home.NeedsYouItem
import com.atomikpanda.groundcontrol.ui.home.approvalsFrom
import com.atomikpanda.groundcontrol.ui.home.blockersFrom
import com.atomikpanda.groundcontrol.ui.home.displayName
import com.atomikpanda.groundcontrol.ui.home.questionsFrom
import com.atomikpanda.groundcontrol.ui.home.sortNeedsYou
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A workspace whose fetch failed (one or more sources errored). */
data class WorkspaceError(val connectionId: String, val workspaceName: String)

/** The merged cross-workspace "Needs you" feed. */
data class HomeFeed(
    val items: List<NeedsYouItem>,
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
            errors = perConn.mapNotNull { it.error },
        )
    }

    private data class ConnResult(val items: List<NeedsYouItem>, val error: WorkspaceError?)

    private suspend fun loadOne(conn: WorkspaceConnection): ConnResult = coroutineScope {
        val specs = async { runCatching { api.listSpecs(conn) } }
        val threads = async { runCatching { api.listThreads(conn) } }
        val tasks = async { runCatching { api.listTasks(conn) } }
        val s = specs.await(); val t = threads.await(); val k = tasks.await()
        val items = buildList {
            s.getOrNull()?.let { addAll(approvalsFrom(conn, it)) }
            t.getOrNull()?.let { addAll(questionsFrom(conn, it)) }
            k.getOrNull()?.let { addAll(blockersFrom(conn, it)) }
        }
        val failed = s.isFailure || t.isFailure || k.isFailure
        ConnResult(items, if (failed) WorkspaceError(conn.id, conn.displayName()) else null)
    }
}
