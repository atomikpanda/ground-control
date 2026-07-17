package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.notify.parseTimestampMillis
import com.atomikpanda.groundcontrol.ui.activity.LiveStatus
import com.atomikpanda.groundcontrol.ui.activity.LiveThresholds
import com.atomikpanda.groundcontrol.ui.activity.liveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChipTest {
    private val base = 1_000_000_000L

    @Test fun working_when_activity_is_recent() {
        assertEquals(LiveStatus.Working, liveStatus(base, base + 30_000L, merged = false))
    }

    @Test fun idle_between_working_and_quiet_windows() {
        // 2 minutes: past the 90s working window, before the 5min quiet threshold.
        assertEquals(LiveStatus.Idle, liveStatus(base, base + 120_000L, merged = false))
    }

    @Test fun quiet_after_five_minutes_reports_minutes() {
        val s = liveStatus(base, base + 480_000L, merged = false) // 8 minutes
        assertTrue(s is LiveStatus.Quiet)
        assertEquals(8L, (s as LiveStatus.Quiet).minutes)
    }

    @Test fun done_when_merged_regardless_of_activity() {
        assertEquals(LiveStatus.Done, liveStatus(base, base + 10_000_000L, merged = true))
    }

    @Test fun unknown_when_absent_not_false_working() {
        assertEquals(LiveStatus.Unknown, liveStatus(null as Long?, base, merged = false))
    }

    @Test fun thresholds_are_named_constants() {
        assertEquals(90_000L, LiveThresholds.WORKING_WINDOW_MS)
        assertEquals(300_000L, LiveThresholds.QUIET_AFTER_MS)
    }

    @Test fun iso_overload_parses_then_classifies() {
        val iso = "2026-07-13T12:00:00Z"
        val parsed = parseTimestampMillis(iso)!!
        // 30s after the stamp → still inside the working window.
        assertEquals(LiveStatus.Working, liveStatus(iso, parsed + 30_000L, merged = false))
    }
}
