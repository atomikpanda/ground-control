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

/** Operator recovery offered for a non-retryable workspace failure. */
enum class WorkspaceErrorAction { RE_PAIR }

/** A workspace whose fetch failed (one or more sources errored). [hostId] is the
 * host it lives on (#471) — null for a manually paired connection. */
data class WorkspaceError(
    val connectionId: String,
    val workspaceName: String,
    val hostId: String? = null,
    /** How many workspaces this row stands for after host-level dedupe. */
    val workspaceCount: Int = 1,
    /** The shared host ladder's diagnosis; null for a legacy/manual connection. */
    val ladderState: HostLadderState? = null,
    /** Operator action that can recover this failure; null means retryable. */
    val action: WorkspaceErrorAction? = null,
)

/**
 * One dead host is ONE fact. Every workspace behind a downed tunnel fails
 * together, and N identical rows would misreport the scale of the outage as
 * well as the cause — so failures sharing a host collapse into one row carrying
 * the count. A null [WorkspaceError.hostId] is "we don't know which host", which
 * is not a shared cause: those rows are never collapsed together.
 */
fun dedupeHostErrors(errors: List<WorkspaceError>): List<WorkspaceError> =
    errors.groupBy { it.hostId }.flatMap { (hostId, group) ->
        if (hostId == null || group.size == 1) group
        else listOf(
            group.first().copy(
                workspaceCount = group.sumOf { it.workspaceCount },
                action = group.firstNotNullOfOrNull { it.action },
            ),
        )
    }

/** Apply the shared ladder to API failures before Home or Queue renders them. A
 * failed workspace request is conservatively an unknown workspace state; host
 * identity and freshness still outrank it. */
fun applyHostLadder(
    errors: List<WorkspaceError>,
    connections: List<WorkspaceConnection>,
    hosts: List<HostConnection>,
    nowMillis: Long,
): List<WorkspaceError> {
    val connectionsById = connections.associateBy { it.id }
    val hostsById = hosts.associateBy { it.hostId }
    return errors.map { error ->
        val connection = connectionsById[error.connectionId]
        val host = error.hostId?.let(hostsById::get)
        if (connection == null || error.hostId == null) error
        else error.copy(
            ladderState = hostLadder(
                hostState = host?.projectedHostState(),
                workspaceState = null,
                runnerState = host?.runnerState,
                secondsSincePhoneContact = host?.lastContactAtMillis?.let { (nowMillis - it) / 1000 },
            ),
        )
    }
}

/** The one wording for an error row, wherever it renders. */
fun workspaceErrorLabel(error: WorkspaceError): String {
    val cause = error.ladderState?.let(::ladderLabel)
    return when {
        error.action == WorkspaceErrorAction.RE_PAIR -> "Re-pair needed — open Settings"
        error.workspaceCount > 1 && cause != null -> "$cause — ${error.workspaceCount} workspaces"
        error.workspaceCount > 1 -> "Host offline — ${error.workspaceCount} workspaces"
        cause != null -> cause
        else -> "${error.workspaceName} unreachable"
    }
}

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

    private fun actionFor(results: List<Result<*>>): WorkspaceErrorAction? =
        WorkspaceErrorAction.RE_PAIR.takeIf {
            results.any { it.exceptionOrNull() is RePairNeededException }
        }

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
        val error = if (failed) {
            WorkspaceError(
                conn.id,
                conn.displayName(),
                conn.hostId.takeIf { conn.hasStableIdentityTuple() },
                action = actionFor(listOf(s, t, k)),
            )
        } else null
        ConnResult(items, notes, error)
    }
}
