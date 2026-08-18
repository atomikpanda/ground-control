package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.DIRECTORY_STALE_S
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.hostLadder
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ONE pure function decides what a connection reads as (#471 AC12). The table is the
 * spec: every row is (hostState, workspaceState, runnerState, secondsSincePhoneContact)
 * → outcome, and the two non-ladder outcomes exist because the phone can be honestly
 * ignorant — the directory may be unreachable, or still reporting `online` for up to
 * DIRECTORY_STALE_S after a tunnel died.
 */
class HostLadderTest {
    private val fresh = 5L

    private val table = listOf(
        // --- healthy -------------------------------------------------------
        row("online", "healthy", "disabled", fresh, HostLadderState.ACTIVE),
        // Until #473 reports an explicitly healthy state, `unknown` is
        // conservative: it must never turn an unobserved runner into active.
        row("online", "healthy", "unknown", fresh, HostLadderState.RUNNER_DEGRADED),
        row("online", "healthy", "idle", fresh, HostLadderState.ACTIVE),
        row("online", "healthy", "active", fresh, HostLadderState.ACTIVE),

        // --- the ladder, least authoritative rung first ---------------------
        row("online", "healthy", "degraded", fresh, HostLadderState.RUNNER_DEGRADED),
        row("online", "degraded", "disabled", fresh, HostLadderState.WORKSPACE_DEGRADED),
        // a degraded workspace outranks a degraded runner
        row("online", "degraded", "degraded", fresh, HostLadderState.WORKSPACE_DEGRADED),
        row("offline", "healthy", "disabled", fresh, HostLadderState.HOST_OFFLINE),
        row("error", "healthy", "disabled", fresh, HostLadderState.HOST_OFFLINE),
        row("connecting", "healthy", "disabled", fresh, HostLadderState.HOST_OFFLINE),
        row("disabled", "healthy", "disabled", fresh, HostLadderState.HOST_OFFLINE),
        // a live twin on our subdomain is a named, operator-actionable fact and
        // outranks "offline" (it is exactly what makes registration fail)
        row("contended", "healthy", "disabled", fresh, HostLadderState.CONTENDED),
        row("duplicate-identity", "healthy", "disabled", fresh, HostLadderState.CONTENDED),
        // nothing works until a human approves the key — the most authoritative rung
        row("pending-approval", "healthy", "disabled", fresh, HostLadderState.PENDING_APPROVAL),
        row("awaiting-enrollment", "degraded", "degraded", fresh, HostLadderState.PENDING_APPROVAL),

        // --- honest unknown #1: GET /hosts failed ---------------------------
        // Last-known is shown; a LAN-reachable host is NOT relabelled offline.
        row(null, "healthy", "disabled", fresh, HostLadderState.DIRECTORY_UNREACHABLE),
        row("", "healthy", "disabled", fresh, HostLadderState.DIRECTORY_UNREACHABLE),

        // --- honest unknown #2: the directory is still reporting `online` ---
        row("online", "healthy", "disabled", DIRECTORY_STALE_S, HostLadderState.STALE),
        row("online", "healthy", "disabled", DIRECTORY_STALE_S + 60, HostLadderState.STALE),
        row("online", "healthy", "disabled", null, HostLadderState.STALE),
        row("online", "healthy", "disabled", DIRECTORY_STALE_S - 1, HostLadderState.ACTIVE),

        // --- unknown/missing collapses to the MOST CONSERVATIVE outcome -----
        row("online", null, "disabled", fresh, HostLadderState.WORKSPACE_DEGRADED),
        row("online", "", "disabled", fresh, HostLadderState.WORKSPACE_DEGRADED),
        row("online", "who-knows", "disabled", fresh, HostLadderState.WORKSPACE_DEGRADED),
        row("online", "healthy", null, fresh, HostLadderState.RUNNER_DEGRADED),
        row("online", "healthy", "who-knows", fresh, HostLadderState.RUNNER_DEGRADED),
        row("who-knows", "healthy", "disabled", fresh, HostLadderState.HOST_OFFLINE),
    )

    @Test fun the_table_is_the_ladder() {
        table.forEach { (inputs, expected) ->
            val (h, w, r, age) = inputs
            assertEquals("$inputs", expected, hostLadder(h, w, r, age))
        }
    }

    @Test fun every_state_is_reachable_and_there_are_exactly_eight() {
        // Six ladder states (active + five faults) plus the two honest unknowns.
        assertEquals(8, HostLadderState.entries.size)
        assertEquals(HostLadderState.entries.toSet(), table.map { it.second }.toSet())
    }

    @Test fun no_input_combination_reads_active_without_a_healthy_host() {
        val states = listOf(null, "", "online", "offline", "error", "connecting", "disabled",
            "contended", "duplicate-identity", "pending-approval", "awaiting-enrollment", "??")
        val workspaces = listOf(null, "", "healthy", "degraded", "??")
        val runners = listOf(null, "disabled", "unknown", "idle", "active", "degraded", "??")
        val ages = listOf(null, 0L, DIRECTORY_STALE_S, DIRECTORY_STALE_S * 10)
        for (h in states) for (w in workspaces) for (r in runners) for (a in ages) {
            if (hostLadder(h, w, r, a) != HostLadderState.ACTIVE) continue
            assertEquals("false active for ($h,$w,$r,$a)", "online", h)
            assertEquals("false active for ($h,$w,$r,$a)", "healthy", w)
        }
    }

    @Test fun a_metarepo_connection_reads_the_same_as_a_single_repo_one() {
        // Assumption 1: #471 is workspace-shape-agnostic. A metarepo entry carries a
        // `repos` list a single-repo entry does not; the ladder must not notice.
        val json = Json { ignoreUnknownKeys = true }
        val single = json.decodeFromString(
            WorkspaceInfo.serializer(),
            """{"id":"ws-1","name":"product","state":"healthy","path":"/src/product",
               "runner":{"enabled":true,"state":"unknown"}}""",
        )
        val metarepo = json.decodeFromString(
            WorkspaceInfo.serializer(),
            """{"id":"ws-2","name":"fleet","state":"healthy","path":"/src/fleet",
               "repos":[{"name":"a","path":"/src/fleet/a"},{"name":"b","path":"/src/fleet/b"}],
               "runner":{"enabled":true,"state":"unknown"}}""",
        )
        for (host in listOf("online", "offline", "pending-approval", null)) {
            assertEquals(
                hostLadder(host, single.state, single.runner?.state, fresh),
                hostLadder(host, metarepo.state, metarepo.runner?.state, fresh),
            )
        }
    }

    private fun row(
        host: String?,
        workspace: String?,
        runner: String?,
        age: Long?,
        expected: HostLadderState,
    ) = Inputs(host, workspace, runner, age) to expected

    private data class Inputs(val host: String?, val workspace: String?, val runner: String?, val age: Long?)
}
