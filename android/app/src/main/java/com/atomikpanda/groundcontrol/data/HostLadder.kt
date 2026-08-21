package com.atomikpanda.groundcontrol.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Mirrors the relay's `host_contract.DIRECTORY_STALE_S`
 * (`3 * REGISTER_INTERVAL_S + MAX_BACKOFF_S`): how long the directory can still
 * read `online` after a tunnel dies. Past it the phone's own last contact, not
 * the directory's claim, is the honest answer.
 */
const val DIRECTORY_STALE_S: Long = 240
const val DIRECTORY_STALE_MS: Long = DIRECTORY_STALE_S * 1_000

/**
 * Re-emit a persisted host list exactly when one of its phone-contact stamps
 * crosses [DIRECTORY_STALE_MS]. [transformLatest] makes ownership explicit:
 * a DataStore update cancels the obsolete deadline, and cancellation of the
 * collecting ViewModel scope cancels the timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<HostConnection>>.emitAtStaleDeadlines(
    nowMillis: () -> Long = System::currentTimeMillis,
): Flow<List<HostConnection>> = transformLatest { hosts ->
    emit(hosts)
    while (true) {
        val now = nowMillis()
        val untilNextDeadline = hosts
            .mapNotNull { it.lastContactAtMillis }
            .map { contactedAt -> contactedAt + DIRECTORY_STALE_MS - now }
            .filter { remaining -> remaining > 0 }
            .minOrNull()
            ?: break
        delay(untilNextDeadline)
        emit(hosts)
    }
}

/**
 * What one connection reads as. Six ladder states — [ACTIVE] plus the five
 * faults below it — and two honest unknowns, which exist because the phone can
 * be ignorant in two specific ways that must not be rendered as `offline`:
 * `GET /hosts` failing ([DIRECTORY_UNREACHABLE]) would otherwise relabel every
 * host, including LAN-reachable ones, and a directory entry can keep reading
 * `online` for [DIRECTORY_STALE_S] after its tunnel died ([STALE]).
 */
enum class HostLadderState {
    /** Nothing works until a human approves the key. */
    PENDING_APPROVAL,
    /** A live twin answers on our subdomain — named and operator-actionable. */
    CONTENDED,
    HOST_OFFLINE,
    WORKSPACE_DEGRADED,
    RUNNER_DEGRADED,
    ACTIVE,
    DIRECTORY_UNREACHABLE,
    STALE,
}

private val SUPPORTED_PENDING_HOST_STATES = setOf("pending-approval", "awaiting-enrollment")
private val CONTENDED = setOf("contended", "duplicate-identity")
private const val ONLINE = "online"
private const val HEALTHY = "healthy"

/** Runner values that are explicitly healthy. A missing block is unknown on
 * persisted/pre-#471 data and therefore degrades conservatively; a host that
 * has no runner reports `disabled` explicitly. */
private val RUNNER_OK = setOf("disabled", "idle", "active")

/**
 * THE ladder — one pure function, so Projects, Home, Queue and Settings cannot
 * disagree about what a connection is doing.
 *
 * Resolved most-authoritative rung first: what the relay decided about the
 * host's identity, then whether the host is up at all, then whether our own
 * evidence is fresh enough to trust that, then the workspace, then the runner.
 *
 * Every unknown collapses DOWNWARD — an unrecognized host state reads offline,
 * an unknown workspace state reads degraded, and never-contacted reads stale —
 * so no combination of missing inputs can produce a false [HostLadderState.ACTIVE].
 *
 * @param hostState directory (or `/health.tunnel`) state; null/blank = the directory
 *   didn't answer.
 * @param workspaceState the workspace's own `state` from `GET /workspaces`.
 * @param runnerState the runner block's `state`; null = no runner block.
 * @param secondsSincePhoneContact since the phone last reached this host itself;
 *   null = never.
 */
fun hostLadder(
    hostState: String?,
    workspaceState: String?,
    runnerState: String?,
    secondsSincePhoneContact: Long?,
): HostLadderState {
    val host = hostState?.trim().orEmpty()
    if (host.isEmpty()) return HostLadderState.DIRECTORY_UNREACHABLE
    if (isSupportedPendingHostState(host)) return HostLadderState.PENDING_APPROVAL
    if (host in CONTENDED) return HostLadderState.CONTENDED
    if (host != ONLINE) return HostLadderState.HOST_OFFLINE
    if (secondsSincePhoneContact == null || secondsSincePhoneContact >= DIRECTORY_STALE_S) {
        return HostLadderState.STALE
    }
    if (workspaceState?.trim() != HEALTHY) return HostLadderState.WORKSPACE_DEGRADED
    if (runnerState !in RUNNER_OK) return HostLadderState.RUNNER_DEGRADED
    return HostLadderState.ACTIVE
}

internal fun isSupportedPendingHostState(state: String?): Boolean =
    state?.trim() in SUPPORTED_PENDING_HOST_STATES

internal fun HostConnection.projectedHostState(): String? =
    state ?: "online".takeIf {
        lastContactAtMillis != null && hostBases().isNotEmpty()
    }


/** Read [hostLadder]'s four inputs off a stored connection and its host. A
 *  projection, not a second ladder — no decision lives here. [nowMillis] is the
 *  caller's clock so this stays a pure function of its inputs. */
fun ladderFor(
    conn: WorkspaceConnection,
    host: HostConnection?,
    nowMillis: Long,
): HostLadderState = hostLadder(
    hostState = host?.projectedHostState(),
    workspaceState = conn.state,
    runnerState = host?.runnerState,
    secondsSincePhoneContact = host?.lastContactAtMillis?.let { (nowMillis - it) / 1000 },
)

/** The ladder for a HOST row rather than a workspace one: the workspace rung is
 *  not applicable (a host is not a workspace), so it is passed as healthy and
 *  the verdict comes from the host and runner rungs alone. */
fun ladderForHost(host: HostConnection, nowMillis: Long): HostLadderState = hostLadder(
    hostState = host.projectedHostState(),
    workspaceState = HEALTHY,
    runnerState = host.runnerState,
    secondsSincePhoneContact = host.lastContactAtMillis?.let { (nowMillis - it) / 1000 },
)

/** One short line for a ladder state, for every surface that renders one. */
fun ladderLabel(state: HostLadderState): String = when (state) {
    HostLadderState.PENDING_APPROVAL -> "Awaiting approval"
    HostLadderState.CONTENDED -> "Identity contended"
    HostLadderState.HOST_OFFLINE -> "Host offline"
    HostLadderState.WORKSPACE_DEGRADED -> "Workspace degraded"
    HostLadderState.RUNNER_DEGRADED -> "Runner degraded"
    HostLadderState.ACTIVE -> "Active"
    HostLadderState.DIRECTORY_UNREACHABLE -> "Relay unreachable — last known"
    HostLadderState.STALE -> "No recent contact"
}
