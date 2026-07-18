package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.notify.OpenThreadRegistry
import com.atomikpanda.groundcontrol.notify.threadKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OpenThreadRegistryTest {

    // The registry is a process-wide singleton; force it clean around each test so ordering
    // between tests can't leak an "open" thread into the next.
    private fun forceClear() {
        OpenThreadRegistry.open("_reset_", "_reset_")
        OpenThreadRegistry.close("_reset_", "_reset_")
    }

    @Before fun setUp() = forceClear()
    @After fun tearDown() = forceClear()

    @Test fun open_records_the_thread_key() {
        OpenThreadRegistry.open("c1", "t1")
        assertEquals(threadKey("c1", "t1"), OpenThreadRegistry.snapshot())
    }

    @Test fun close_matching_thread_clears_the_signal() {
        OpenThreadRegistry.open("c1", "t1")
        OpenThreadRegistry.close("c1", "t1")
        assertNull(OpenThreadRegistry.snapshot())
    }

    @Test fun close_non_matching_thread_is_a_no_op() {
        OpenThreadRegistry.open("c1", "t1")
        OpenThreadRegistry.close("c1", "other")   // stale close of a thread that isn't open
        assertEquals(threadKey("c1", "t1"), OpenThreadRegistry.snapshot())
    }

    @Test fun opening_b_then_a_late_close_of_a_keeps_b() {
        // navigate A -> B race: B.open lands, then A's late close must not wipe B.
        OpenThreadRegistry.open("c1", "B")
        OpenThreadRegistry.close("c1", "A")
        assertEquals(threadKey("c1", "B"), OpenThreadRegistry.snapshot())
    }
}
