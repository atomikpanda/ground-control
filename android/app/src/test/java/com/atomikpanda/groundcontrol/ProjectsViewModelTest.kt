package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.projects.projectRows
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectsViewModelTest {
    @Test fun replacement_projects_projection_uses_latest_connection_identity() {
        val replaced = WorkspaceConnection("workspace", "https://new/workspaces/a", "new-token", "Renamed")

        val rows = projectRows(listOf(replaced))

        assertEquals(listOf("workspace"), rows.map { it.connectionId })
        assertEquals("Renamed", rows.single().name)
        assertEquals("workspace/workspace", rows.single().route)
    }

    @Test fun empty_ready_connections_projects_projection_is_empty() {
        assertEquals(emptyList<Any>(), projectRows(emptyList()))
    }
}
