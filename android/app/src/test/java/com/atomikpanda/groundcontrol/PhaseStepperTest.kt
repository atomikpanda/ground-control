package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.activity.PhaseStep
import com.atomikpanda.groundcontrol.ui.activity.phaseStepFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhaseStepperTest {
    @Test fun maps_task_phases_to_steps() {
        assertEquals(PhaseStep.DISPATCHED, phaseStepFor(null, false, dispatched = true))
        assertEquals(PhaseStep.PLANNING, phaseStepFor("plan", false))
        assertEquals(PhaseStep.BUILDING, phaseStepFor("dev", false))
        assertEquals(PhaseStep.REVIEW, phaseStepFor("review", false))
        assertEquals(PhaseStep.DONE, phaseStepFor("run", false))
    }

    @Test fun done_flag_wins_over_phase() {
        assertEquals(PhaseStep.DONE, phaseStepFor("dev", true))
    }

    @Test fun missing_or_unknown_phase_does_not_claim_dispatch() {
        assertNull(phaseStepFor(null, false))
        assertNull(phaseStepFor("weird", false, dispatched = true))
    }

    @Test fun reported_progress_wins_over_the_specs_dispatch_status() {
        assertEquals(PhaseStep.REVIEW, phaseStepFor("review", false, dispatched = true))
        assertEquals(PhaseStep.DONE, phaseStepFor(null, true, dispatched = true))
    }

}
