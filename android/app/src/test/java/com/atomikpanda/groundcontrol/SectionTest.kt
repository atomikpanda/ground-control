package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.nav.Section
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionTest {
    @Test fun exactly_three_destinations_home_tasks_settings() {
        assertEquals(listOf("home", "tasks", "settings"), Section.entries.map { it.route })
    }
}
