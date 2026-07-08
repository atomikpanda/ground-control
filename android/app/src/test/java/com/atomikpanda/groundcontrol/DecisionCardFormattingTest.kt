package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.ui.messages.formatCommentMessage
import com.atomikpanda.groundcontrol.ui.messages.formatMultiSelectMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionCardFormattingTest {
    @Test fun comment_message_uses_one_based_index_and_em_dash() {
        val msg = formatCommentMessage(0, "Ship it", "but gate behind a flag")
        assertEquals("1. Ship it — but gate behind a flag", msg)
    }

    @Test fun comment_message_keeps_option_text_verbatim() {
        val msg = formatCommentMessage(2, "Do the thing (carefully)", "watch for nulls")
        assertEquals("3. Do the thing (carefully) — watch for nulls", msg)
    }

    @Test fun multiselect_message_matches_spec_example() {
        val options = listOf("a", "b", "c")
        val msg = formatMultiSelectMessage(options, listOf(0, 2))
        assertEquals("Selected: 1. a; 3. c", msg)
    }

    @Test fun multiselect_message_sorts_regardless_of_selection_order() {
        val options = listOf("a", "b", "c")
        val msg = formatMultiSelectMessage(options, listOf(2, 0))
        assertEquals("Selected: 1. a; 3. c", msg)
    }

    @Test fun multiselect_message_single_selection() {
        val options = listOf("a", "b")
        val msg = formatMultiSelectMessage(options, listOf(1))
        assertEquals("Selected: 2. b", msg)
    }

    @Test fun multiselect_message_keeps_option_text_verbatim() {
        val options = listOf("Add index (SQLite)", "Backfill nulls")
        val msg = formatMultiSelectMessage(options, listOf(0, 1))
        assertEquals("Selected: 1. Add index (SQLite); 2. Backfill nulls", msg)
    }
}
