package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.notify.activeDecision
import com.atomikpanda.groundcontrol.notify.decisionActionOptions
import com.atomikpanda.groundcontrol.notify.messageTimestamps
import com.atomikpanda.groundcontrol.notify.parseTimestampMillis
import com.atomikpanda.groundcontrol.notify.recentMessages
import com.atomikpanda.groundcontrol.notify.replyText
import com.atomikpanda.groundcontrol.notify.stripMarkdownForNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFormatTest {

    // --- timestamp parsing -------------------------------------------------

    @Test fun parses_iso_instant_with_z() {
        assertEquals(1751284800000L, parseTimestampMillis("2025-06-30T12:00:00Z"))
    }

    @Test fun parses_iso_offset_datetime() {
        // 2025-06-30T13:00:00+01:00 == 12:00:00Z
        assertEquals(parseTimestampMillis("2025-06-30T12:00:00Z"), parseTimestampMillis("2025-06-30T13:00:00+01:00"))
    }

    @Test fun null_or_garbage_timestamp_is_null() {
        assertNull(parseTimestampMillis(null))
        assertNull(parseTimestampMillis(""))
        assertNull(parseTimestampMillis("x"))
    }

    @Test fun message_timestamps_fall_back_to_now_relative_in_order() {
        val now = 1_000_000_000L
        val stamps = messageTimestamps(listOf("x", null, "2025-06-30T12:00:00Z"), now)
        // First two are unparseable -> now-relative, preserving order (earlier < later).
        assertEquals(now - 2000L, stamps[0])
        assertEquals(now - 1000L, stamps[1])
        assertEquals(1751284800000L, stamps[2])
        assertTrue(stamps[0] < stamps[1])
    }

    // --- recent messages ---------------------------------------------------

    private fun msg(id: String, role: String = "agent", kind: String = "note", decision: Decision? = null) =
        Message(id = id, threadId = "t", role = role, text = "t-$id", kind = kind, decision = decision)

    @Test fun recent_messages_takes_the_tail() {
        val all = (1..10).map { msg("m$it") }
        val recent = recentMessages(all, limit = 6)
        assertEquals(6, recent.size)
        assertEquals("m5", recent.first().id)
        assertEquals("m10", recent.last().id)
    }

    @Test fun recent_messages_returns_all_when_under_limit() {
        val all = listOf(msg("a"), msg("b"))
        assertEquals(2, recentMessages(all, limit = 6).size)
    }

    // --- active decision ---------------------------------------------------

    @Test fun active_decision_is_the_last_unanswered_one() {
        val d = Decision(options = listOf("A", "B"))
        val messages = listOf(
            msg("m1", role = "human"),
            msg("m2", role = "agent", kind = "decision", decision = d),
        )
        assertEquals(d, activeDecision(messages))
    }

    @Test fun active_decision_null_when_a_human_replied_after_it() {
        val d = Decision(options = listOf("A", "B"))
        val messages = listOf(
            msg("m1", role = "agent", kind = "decision", decision = d),
            msg("m2", role = "human"),
        )
        assertNull(activeDecision(messages))
    }

    // --- decision action options -------------------------------------------

    @Test fun single_select_puts_recommended_first_then_caps() {
        val d = Decision(options = listOf("A", "B", "C", "D"), recommended = 2)
        val opts = decisionActionOptions(d, cap = 2)
        assertEquals(2, opts.size)
        assertEquals("C", opts[0].text) // recommended first
        assertEquals(2, opts[0].index)
        assertEquals("A", opts[1].text) // then original order, skipping recommended
    }

    @Test fun single_select_without_recommendation_keeps_order() {
        val d = Decision(options = listOf("A", "B", "C"))
        val opts = decisionActionOptions(d, cap = 3)
        assertEquals(listOf("A", "B", "C"), opts.map { it.text })
    }

    @Test fun cap_of_two_limits_to_top_two() {
        val d = Decision(options = listOf("A", "B", "C", "D"))
        assertEquals(listOf("A", "B"), decisionActionOptions(d, cap = 2).map { it.text })
    }

    @Test fun multi_select_renders_no_option_buttons() {
        val d = Decision(options = listOf("A", "B", "C"), multi = true)
        assertTrue(decisionActionOptions(d, cap = 3).isEmpty())
    }

    @Test fun null_or_empty_decision_renders_no_buttons() {
        assertTrue(decisionActionOptions(null, cap = 3).isEmpty())
        assertTrue(decisionActionOptions(Decision(options = emptyList()), cap = 3).isEmpty())
    }

    @Test fun out_of_range_recommendation_is_ignored() {
        val d = Decision(options = listOf("A", "B"), recommended = 9)
        assertEquals(listOf("A", "B"), decisionActionOptions(d, cap = 2).map { it.text })
    }

    // --- reply text mapping ------------------------------------------------

    @Test fun reply_text_trims_and_nulls_blank() {
        assertEquals("hello", replyText("  hello  "))
        assertNull(replyText(null))
        assertNull(replyText("   "))
    }

    // --- markdown stripping ------------------------------------------------

    @Test fun strips_fenced_code_fences_keeping_content() {
        val md = "before\n```kotlin\nval x = 1\n```\nafter"
        val out = stripMarkdownForNotification(md)
        assertFalse(out.contains("```"))
        assertTrue(out.contains("val x = 1"))
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
    }

    @Test fun strips_emphasis_and_headings_and_inline_code() {
        assertEquals("Title", stripMarkdownForNotification("## Title"))
        assertEquals("bold and italic", stripMarkdownForNotification("**bold** and *italic*"))
        assertEquals("run this", stripMarkdownForNotification("run `this`"))
    }

    @Test fun strips_single_underscore_italic() {
        assertEquals("bold and italic", stripMarkdownForNotification("__bold__ and _italic_"))
        assertEquals("run this please", stripMarkdownForNotification("run _this_ please"))
    }

    @Test fun underscore_italic_does_not_mangle_snake_case() {
        val prose = "the snake_case_word stays intact"
        assertEquals(prose, stripMarkdownForNotification(prose))
    }

    @Test fun converts_bullets_and_links() {
        assertEquals("• one\n• two", stripMarkdownForNotification("- one\n- two"))
        assertEquals("see docs", stripMarkdownForNotification("see [docs](https://example.com)"))
    }

    @Test fun collapses_excess_blank_lines_and_trims() {
        assertEquals("a\n\nb", stripMarkdownForNotification("\n\na\n\n\n\nb\n\n"))
    }

    @Test fun leaves_plain_prose_untouched() {
        val prose = "Just a normal sentence with 2 + 2 = 4."
        assertEquals(prose, stripMarkdownForNotification(prose))
    }
}
