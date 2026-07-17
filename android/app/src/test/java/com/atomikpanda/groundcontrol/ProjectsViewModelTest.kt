package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.projects.projectRows
import com.atomikpanda.groundcontrol.ui.projects.workspaceRoute
import com.atomikpanda.groundcontrol.ui.theme.WorkspacePalette
import com.atomikpanda.groundcontrol.ui.theme.autoColor
import com.atomikpanda.groundcontrol.ui.theme.colorFromHex
import com.atomikpanda.groundcontrol.ui.theme.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectsViewModelTest {
    private val conns = listOf(
        WorkspaceConnection("a", "http://a:47100", null, "acme"),
        WorkspaceConnection("b", "http://b:47100", null, "beta", colorOverride = "#FFD32F2F", glyphOverride = "B"),
        WorkspaceConnection("c", "http://c:47100", null, ""),   // blank name → baseUrl
    )

    @Test fun one_row_per_connection_in_order() {
        val rows = projectRows(conns)
        assertEquals(listOf("a", "b", "c"), rows.map { it.connectionId })
    }

    @Test fun row_route_targets_the_existing_workspace_detail() {
        assertEquals("workspace/a", projectRows(conns)[0].route)
        assertEquals("workspace/b", workspaceRoute("b"))
    }

    @Test fun row_identity_is_resolved_override_or_auto() {
        val rows = projectRows(conns)
        assertEquals(autoColor("acme"), rows[0].identity.color)   // auto
        assertEquals("A", rows[0].identity.glyph)
        assertEquals(colorFromHex("#FFD32F2F"), rows[1].identity.color) // override
        assertEquals("B", rows[1].identity.glyph)
        assertEquals("H", rows[2].identity.glyph)                 // "http://..." → H
    }

    @Test fun directory_builds_offline_from_connections_only() {
        assertEquals(3, projectRows(conns).size)
    }

    @Test fun palette_swatches_encode_to_parseable_hex() {
        for (c in WorkspacePalette.swatches) {
            assertEquals(c, colorFromHex(c.toHex()))
        }
    }
}
