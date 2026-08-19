package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.WorkspaceError
import com.atomikpanda.groundcontrol.data.WorkspaceErrorAction
import com.atomikpanda.groundcontrol.data.applyHostLadder
import com.atomikpanda.groundcontrol.data.dedupeHostErrors
import com.atomikpanda.groundcontrol.data.workspaceErrorLabel
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
        assertEquals("Host offline — 3 workspaces", workspaceErrorLabel(out[0]))
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
        assertEquals("ws-a unreachable", workspaceErrorLabel(out[0]))
    }

    @Test fun a_single_failure_on_a_known_host_keeps_the_workspace_wording() {
        val out = dedupeHostErrors(listOf(err("a", "h-1"), err("b", null)))
        assertEquals(2, out.size)
        assertEquals("ws-a unreachable", workspaceErrorLabel(out.first { it.hostId == "h-1" }))
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
        assertEquals("Host offline", workspaceErrorLabel(error))
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
        assertEquals("Workspace degraded", workspaceErrorLabel(error))
    }

    @Test fun re_pair_errors_are_an_explicit_settings_action() {
        val error = WorkspaceError(
            connectionId = "a",
            workspaceName = "ws-a",
            hostId = "h-1",
            action = WorkspaceErrorAction.RE_PAIR,
        )
        assertEquals("Re-pair needed — open Settings", workspaceErrorLabel(error))
        val deduped = dedupeHostErrors(listOf(err("a", "h-1"), error))
        assertEquals(WorkspaceErrorAction.RE_PAIR, deduped.single().action)
    }

    @Test fun dedupe_is_stable_and_empty_safe() {
        assertEquals(emptyList<WorkspaceError>(), dedupeHostErrors(emptyList()))
        val one = listOf(err("a", "h-1"))
        assertEquals(one, dedupeHostErrors(one))
    }
}
