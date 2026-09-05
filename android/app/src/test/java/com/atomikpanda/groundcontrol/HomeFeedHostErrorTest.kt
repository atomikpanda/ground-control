package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.data.WorkspaceAvailabilityTone
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.data.WorkspaceErrorAction
import com.atomikpanda.groundcontrol.data.applyHostLadder
import com.atomikpanda.groundcontrol.data.dedupeHostErrors
import com.atomikpanda.groundcontrol.data.legacyRequestTone
import com.atomikpanda.groundcontrol.data.workspaceErrorTone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One dead host is ONE fact. Before #471 a workspace was its own address, so a
 * per-workspace error row was the whole truth; now three workspaces behind one
 * tunnel fail together and three identical rows would be three lies about scale.
 */
class HomeFeedHostErrorTest {
    private fun err(id: String, host: String?) = WorkspaceError(id, "ws-$id", host)

    @Test fun three_workspaces_on_one_dead_host_render_one_row() {
        val out = dedupeHostErrors(listOf(err("a", "h-1"), err("b", "h-1"), err("c", "h-1")))
        assertEquals(1, out.size)
        assertEquals("h-1", out[0].hostId)
        assertEquals(3, out[0].workspaceCount)
    }

    @Test fun two_dead_hosts_stay_two_rows() {
        // AC5: neither host's failure hides the other.
        val out = dedupeHostErrors(listOf(err("a", "h-1"), err("b", "h-2"), err("c", "h-1")))
        assertEquals(listOf("h-1", "h-2"), out.map { it.hostId })
        assertEquals(listOf(2, 1), out.map { it.workspaceCount })
    }

    @Test fun manual_connections_are_never_collapsed_together() {
        // hostId == null is "we don't know which host" — collapsing those would
        // invent a shared cause that isn't there.
        val out = dedupeHostErrors(listOf(err("a", null), err("b", null)))
        assertEquals(2, out.size)
        assertEquals(listOf("a", "b"), out.map { it.connectionId })
    }

    @Test fun a_known_host_failure_stays_independent_from_manual_connections() {
        val out = dedupeHostErrors(listOf(err("a", "h-1"), err("b", null)))
        assertEquals(listOf("h-1", null), out.map { it.hostId })
        assertEquals(listOf(1, 1), out.map { it.workspaceCount })
    }

    @Test fun home_and_queue_errors_use_the_shared_host_ladder() {
        val connection = WorkspaceConnection(
            id = "a",
            baseUrl = "https://host/workspaces/ws-a",
            workspaceName = "ws-a",
            hostId = "h-1",
            workspaceId = "ws-a",
            state = "healthy",
        )
        val offline = HostConnection(
            hostId = "h-1",
            publicUrl = "https://host",
            state = "offline",
            lastContactAtMillis = 1_000,
        )
        val error = applyHostLadder(
            errors = listOf(err("a", "h-1")),
            connections = listOf(connection),
            hosts = listOf(offline),
            nowMillis = 2_000,
        ).single()
        assertEquals(HostLadderState.HOST_OFFLINE, error.ladderState)
    }

    @Test fun a_directly_reached_host_projects_phone_contact_when_the_directory_is_down() {
        val connection = WorkspaceConnection(
            id = "a",
            baseUrl = "http://lan/workspaces/ws-a",
            workspaceName = "ws-a",
            hostId = "h-1",
            workspaceId = "ws-a",
            state = "healthy",
        )
        val host = HostConnection(
            hostId = "h-1",
            relayDomain = "relay.example.com",
            publicUrl = "https://h-1.relay.example.com",
            directUrl = "http://lan",
            state = null,
            lastContactAtMillis = 1_000,
        )
        val error = applyHostLadder(
            errors = listOf(err("a", "h-1")),
            connections = listOf(connection),
            hosts = listOf(host),
            nowMillis = 2_000,
        ).single()
        assertEquals(HostLadderState.WORKSPACE_DEGRADED, error.ladderState)
    }

    @Test fun re_pair_errors_are_an_explicit_settings_action() {
        val error = WorkspaceError(
            connectionId = "a",
            workspaceName = "ws-a",
            hostId = "h-1",
            action = WorkspaceErrorAction.RE_PAIR,
        )
        val deduped = dedupeHostErrors(listOf(err("a", "h-1"), error))
        assertEquals(WorkspaceErrorAction.RE_PAIR, deduped.single().action)
    }

    @Test fun availability_tone_keeps_unknown_contact_neutral_and_recovery_states_actionable() {
        val error = err("a", "h-1")
        assertEquals(
            WorkspaceAvailabilityTone.NEUTRAL,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.HOST_OFFLINE)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.NEUTRAL,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.STALE)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.NEUTRAL,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.DIRECTORY_UNREACHABLE)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.PENDING_APPROVAL)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.CONTENDED)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.WORKSPACE_DEGRADED)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            workspaceErrorTone(error.copy(ladderState = HostLadderState.RUNNER_DEGRADED)),
        )
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            workspaceErrorTone(error.copy(action = WorkspaceErrorAction.RE_PAIR)),
        )
    }

    @Test fun legacy_auth_failures_remain_actionable() {
        assertEquals(WorkspaceAvailabilityTone.NEUTRAL, legacyRequestTone(IllegalStateException()))
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            legacyRequestTone(AuthException("unauthorized")),
        )
        assertEquals(
            WorkspaceAvailabilityTone.ACTIONABLE,
            legacyRequestTone(RePairNeededException("https://host")),
        )
    }

    @Test fun dedupe_is_stable_and_empty_safe() {
        assertEquals(emptyList<WorkspaceError>(), dedupeHostErrors(emptyList()))
        val one = listOf(err("a", "h-1"))
        assertEquals(one, dedupeHostErrors(one))
    }
}
