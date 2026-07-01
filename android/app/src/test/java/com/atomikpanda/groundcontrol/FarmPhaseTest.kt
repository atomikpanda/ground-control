package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.farm.FarmPhase
import com.atomikpanda.groundcontrol.ui.farm.PhaseGroup
import com.atomikpanda.groundcontrol.ui.farm.groupByPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class FarmPhaseTest {
    private fun item(id: String, phase: String, updated: String? = null) =
        WorkItemSummary(id = id, kind = "feature", title = id, phase = phase, updatedAt = updated)

    @Test
    fun groups_in_pipeline_order_done_last_empty_sections_dropped() {
        val groups = groupByPhase(
            listOf(item("a", "review"), item("b", "inbox"), item("c", "done"), item("d", "in_flight")),
        )
        // inbox, in_flight, review, done — shaping/ready dropped (empty)
        assertEquals(
            listOf(FarmPhase.INBOX, FarmPhase.IN_FLIGHT, FarmPhase.REVIEW, FarmPhase.DONE),
            groups.map { it.phase },
        )
    }

    @Test
    fun within_a_phase_newest_first() {
        val groups = groupByPhase(
            listOf(item("old", "inbox", "2026-07-01T00:00:00Z"), item("new", "inbox", "2026-07-02T00:00:00Z")),
        )
        assertEquals(listOf("new", "old"), groups.single().items.map { it.id })
    }

    @Test
    fun unknown_phase_is_dropped() {
        assertEquals(emptyList<PhaseGroup>(), groupByPhase(listOf(item("x", "bogus"))))
    }
}
