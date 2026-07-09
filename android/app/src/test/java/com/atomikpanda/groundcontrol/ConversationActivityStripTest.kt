package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.ui.messages.journalStripLine
import com.atomikpanda.groundcontrol.ui.messages.relativeTimeAgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MOS-224 in-thread activity strip: pure formatting helpers (relative time + the
 *  per-entry label). Kept separate from Compose so these are plain JVM unit tests. */
class ConversationActivityStripTest {

    private val t0 = java.time.Instant.parse("2026-06-22T10:00:00Z").toEpochMilli()

    @Test fun relative_time_just_now_under_a_minute() {
        assertEquals("just now", relativeTimeAgo("2026-06-22T09:59:30Z", t0))
    }

    @Test fun relative_time_minutes() {
        assertEquals("3m ago", relativeTimeAgo("2026-06-22T09:57:00Z", t0))
    }

    @Test fun relative_time_hours() {
        assertEquals("2h ago", relativeTimeAgo("2026-06-22T08:00:00Z", t0))
    }

    @Test fun relative_time_days() {
        assertEquals("5d ago", relativeTimeAgo("2026-06-17T10:00:00Z", t0))
    }

    @Test fun relative_time_accepts_offset_form_not_just_z() {
        assertEquals("just now", relativeTimeAgo("2026-06-22T09:59:45+00:00", t0))
    }

    @Test fun relative_time_null_on_unparseable_timestamp() {
        assertNull(relativeTimeAgo("not-a-date", t0))
    }

    @Test fun relative_time_never_negative_for_a_clock_skewed_future_timestamp() {
        // A journal entry timestamped slightly ahead of "now" (clock skew) must still read as
        // "just now", not throw or show a negative duration.
        assertEquals("just now", relativeTimeAgo("2026-06-22T10:00:30Z", t0))
    }

    @Test fun strip_line_prefers_action_over_message() {
        val e = JournalEntry(timestamp = "2026-06-22T09:57:00Z", message = "long-form note", action = "wrote parser")
        assertEquals("wrote parser · 3m ago", journalStripLine(e, t0))
    }

    @Test fun strip_line_falls_back_to_message_when_action_absent() {
        val e = JournalEntry(timestamp = "2026-06-22T09:57:00Z", message = "spawned")
        assertEquals("spawned · 3m ago", journalStripLine(e, t0))
    }

    @Test fun strip_line_falls_back_to_message_when_action_blank() {
        val e = JournalEntry(timestamp = "2026-06-22T09:57:00Z", message = "spawned", action = "  ")
        assertEquals("spawned · 3m ago", journalStripLine(e, t0))
    }

    @Test fun strip_line_omits_ago_suffix_when_timestamp_unparseable() {
        val e = JournalEntry(timestamp = "garbage", message = "spawned")
        assertEquals("spawned", journalStripLine(e, t0))
    }

    @Test fun strip_line_truncates_a_long_label_cleanly() {
        val long = "x".repeat(120)
        val e = JournalEntry(timestamp = "2026-06-22T09:57:00Z", message = long)
        val line = journalStripLine(e, t0)
        // 60-char label cap + " · 3m ago" suffix; must end with the ellipsis, not overflow.
        assertEquals(60, line.substringBefore(" · ").length)
        assertEquals('…', line.substringBefore(" · ").last())
    }
}
