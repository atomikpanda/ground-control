package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.activity.PhaseStep
import com.atomikpanda.groundcontrol.ui.activity.phaseStepFor
import org.junit.Assert.assertEquals
import org.junit.Test

class PhaseStepperTest {
    @Test fun maps_task_phases_to_steps() {
        assertEquals(PhaseStep.DISPATCHED, phaseStepFor(null, false))
        assertEquals(PhaseStep.PLANNING, phaseStepFor("plan", false))
        assertEquals(PhaseStep.BUILDING, phaseStepFor("dev", false))
        assertEquals(PhaseStep.REVIEW, phaseStepFor("review", false))
        assertEquals(PhaseStep.DONE, phaseStepFor("run", false))
    }

    @Test fun done_flag_wins_over_phase() {
        assertEquals(PhaseStep.DONE, phaseStepFor("dev", true))
    }

    @Test fun unknown_phase_degrades_to_dispatched() {
        assertEquals(PhaseStep.DISPATCHED, phaseStepFor("weird", false))
    }
}
