package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.nav.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionTest {
    @Test fun exactly_four_destinations_home_queue_tasks_settings() {
        assertEquals(listOf("home", "queue", "tasks", "settings"), Section.entries.map { it.route })
    }

    @Test fun queue_tab_present_after_home() {
        val routes = Section.entries.map { it.route }
        assertTrue(routes.contains("queue"))
        assertEquals(0, routes.indexOf("home"))
        assertEquals(1, routes.indexOf("queue"))   // Queue is the 2nd tab, Home stays start destination
    }
}
