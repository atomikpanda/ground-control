package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.nav.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionTest {
    @Test fun five_destinations_home_queue_tasks_projects_settings() {
        assertEquals(
            listOf("home", "queue", "tasks", "projects", "settings"),
            Section.entries.map { it.route },
        )
    }

    @Test fun home_is_start_and_projects_precedes_settings() {
        val routes = Section.entries.map { it.route }
        assertEquals(0, routes.indexOf("home"))
        assertEquals(1, routes.indexOf("queue"))
        assertTrue(routes.indexOf("projects") < routes.indexOf("settings"))
    }
}
