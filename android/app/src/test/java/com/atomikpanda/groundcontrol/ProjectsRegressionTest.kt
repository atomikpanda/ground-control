package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.projects.ProjectsViewModel
import com.atomikpanda.groundcontrol.ui.projects.projectRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor

/** ac8: the Projects directory is orientation-only. It builds purely from the stored connection
 *  list with NO serve call, and must not introduce any app-scoping. */
class ProjectsRegressionTest {
    @Test fun projects_view_model_depends_only_on_connections_repository_no_specapi() {
        val ctors: Array<Constructor<*>> = ProjectsViewModel::class.java.declaredConstructors
        val paramTypes = ctors.flatMap { it.parameterTypes.map { p -> p.simpleName } }
        assertTrue("ProjectsViewModel must not take SpecApi", paramTypes.none { it == "SpecApi" })
        assertTrue("ProjectsViewModel must not take HttpClient", paramTypes.none { it == "HttpClient" })
    }

    @Test fun directory_lists_every_workspace_no_scoping_or_filtering() {
        val all = listOf(
            WorkspaceConnection("a", "http://a", null, "acme"),
            WorkspaceConnection("b", "http://b", null, "beta"),
            WorkspaceConnection("c", "http://c", null, "gamma"),
        )
        assertEquals(all.map { it.id }, projectRows(all).map { it.connectionId })
    }
}
