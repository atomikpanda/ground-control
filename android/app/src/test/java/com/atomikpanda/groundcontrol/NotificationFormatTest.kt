package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.notify.activeDecision
import com.atomikpanda.groundcontrol.notify.decisionActionOptions
import com.atomikpanda.groundcontrol.notify.decisionOptionsBody
import com.atomikpanda.groundcontrol.notify.messageTimestamps
import com.atomikpanda.groundcontrol.notify.parseTimestampMillis
import com.atomikpanda.groundcontrol.notify.needsYouNotificationId
import com.atomikpanda.groundcontrol.notify.optionButtonLabel
import com.atomikpanda.groundcontrol.notify.recentMessages
import com.atomikpanda.groundcontrol.notify.replyText
import com.atomikpanda.groundcontrol.notify.shouldSuppressNotification
import com.atomikpanda.groundcontrol.notify.stripMarkdownForNotification
import com.atomikpanda.groundcontrol.notify.threadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // --- notification id / thread key (shared by post + cancel, #378) ---------

    @Test fun thread_key_is_conn_pipe_thread() {
        assertEquals("c1|t1", threadKey("c1", "t1"))
    }

    @Test fun needs_you_notification_id_matches_the_legacy_derivation() {
        // Must equal the exact string hash AndroidNotifier posted under before extraction,
        // so a cancel keyed by the same id dismisses the same notification.
        assertEquals(("c1" + "|" + "t1").hashCode(), needsYouNotificationId("c1", "t1"))
        assertEquals("c1|t1".hashCode(), needsYouNotificationId("c1", "t1"))
    }

    @Test fun needs_you_notification_id_is_distinct_per_thread_and_connection() {
        assertNotEquals(needsYouNotificationId("c1", "t1"), needsYouNotificationId("c1", "t2"))
        assertNotEquals(needsYouNotificationId("c1", "t1"), needsYouNotificationId("c2", "t1"))
    }

    // --- option button short label (#379) -----------------------------------

    @Test fun option_label_keeps_a_short_single_word() {
        assertEquals("Yes", optionButtonLabel("Yes"))
    }

    @Test fun option_label_keeps_a_short_multiword_phrase() {
        assertEquals("Ship it now", optionButtonLabel("Ship it now"))
    }

    @Test fun option_label_truncates_by_word_count_with_ellipsis() {
        assertEquals("Merge the pull…", optionButtonLabel("Merge the pull request into main"))
    }

    @Test fun option_label_hard_caps_a_long_single_word() {
        val out = optionButtonLabel("Supercalifragilisticexpialidocious")
        assertTrue(out.endsWith("…"))
        assertEquals(25, out.length)  // 24-char cap + the ellipsis
    }

    @Test fun option_label_blank_in_blank_out() {
        assertEquals("", optionButtonLabel(""))
        assertEquals("", optionButtonLabel("   "))
    }

    @Test fun option_label_trims_surrounding_whitespace() {
        assertEquals("Approve", optionButtonLabel("  Approve  "))
    }

    // --- full decision options rendered into the chat body (#379) ------------

    @Test fun options_body_numbers_all_options_full_text() {
        val d = Decision(options = listOf("Ship it to production now", "Hold for review"))
        assertEquals("1. Ship it to production now\n2. Hold for review", decisionOptionsBody(d))
    }

    @Test fun options_body_flags_the_recommended_option() {
        val d = Decision(options = listOf("A", "B", "C"), recommended = 1)
        assertEquals("1. A\n2. B (recommended)\n3. C", decisionOptionsBody(d))
    }

    @Test fun options_body_lists_options_even_for_multi_select() {
        val d = Decision(options = listOf("A", "B"), multi = true)
        assertEquals("1. A\n2. B", decisionOptionsBody(d))
    }

    @Test fun options_body_is_null_when_no_options_or_no_decision() {
        assertNull(decisionOptionsBody(null))
        assertNull(decisionOptionsBody(Decision(options = emptyList())))
    }

    // --- foreground open-thread suppression predicate (#378) ------------------

    @Test fun suppress_when_the_open_thread_matches() {
        assertTrue(shouldSuppressNotification(threadKey("c1", "t1"), "c1", "t1"))
    }

    @Test fun do_not_suppress_a_different_open_thread() {
        assertFalse(shouldSuppressNotification(threadKey("c1", "other"), "c1", "t1"))
    }

    @Test fun do_not_suppress_when_nothing_is_open() {
        assertFalse(shouldSuppressNotification(null, "c1", "t1"))
    }
}
