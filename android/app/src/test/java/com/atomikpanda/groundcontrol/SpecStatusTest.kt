package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.SpecGroup
import com.atomikpanda.groundcontrol.data.groupForStatus
import com.atomikpanda.groundcontrol.data.orderedGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecStatusTest {
    @Test fun maps_every_status_to_its_group() {
        assertEquals(SpecGroup.NEEDS_REVIEW, groupForStatus("needs_review"))
        assertEquals(SpecGroup.NEEDS_REVIEW, groupForStatus("needs_clarification"))
        assertEquals(SpecGroup.READY_TO_DISPATCH, groupForStatus("approved"))
        assertEquals(SpecGroup.IN_IMPLEMENTATION, groupForStatus("dispatched"))
        assertEquals(SpecGroup.DRAFTING, groupForStatus("captured"))
        assertEquals(SpecGroup.DRAFTING, groupForStatus("drafting"))
        assertEquals(SpecGroup.DONE, groupForStatus("implemented"))
    }

    @Test fun archived_and_unknown_have_no_group() {
        assertNull(groupForStatus("archived"))   // excluded from inbox
        assertNull(groupForStatus("bogus"))
    }

    @Test fun ordered_groups_are_actionable_first() {
        assertEquals(
            listOf(
                SpecGroup.NEEDS_REVIEW, SpecGroup.READY_TO_DISPATCH,
                SpecGroup.IN_IMPLEMENTATION, SpecGroup.DRAFTING, SpecGroup.DONE,
            ),
            orderedGroups(),
        )
    }
}
