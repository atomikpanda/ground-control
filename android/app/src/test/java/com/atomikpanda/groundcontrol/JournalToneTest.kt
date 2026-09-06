package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.JournalEntry
import com.atomikpanda.groundcontrol.ui.components.JournalTone
import com.atomikpanda.groundcontrol.ui.components.journalTone
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalToneTest {
    @Test fun failed_tests_cannot_be_hidden_by_success_category_or_open_question() {
        val entry = JournalEntry(
            timestamp = "2026-09-06T12:00:00Z",
            message = "The earlier verification passed.",
            testState = "fail",
            category = "success",
            openQuestion = "Which failure should we address first?",
        )
        assertEquals(JournalTone.ERROR, journalTone(entry))
    }

    @Test fun open_question_remains_distinct_after_passing_tests_but_not_above_mixed_results() {
        val entry = JournalEntry("now", "Verification finished", testState = "pass", openQuestion = "Ship it?")
        assertEquals(JournalTone.QUESTION, journalTone(entry))
        assertEquals(JournalTone.WARNING, journalTone(entry.copy(testState = "mixed")))
    }

    @Test fun unstructured_words_and_unknown_categories_are_not_treated_as_status() {
        val entry = JournalEntry(
            timestamp = "now",
            message = "Investigating a failed request; no errors remain.",
            category = "failure-investigation",
            testState = "future-state",
        )
        assertEquals(JournalTone.ROUTINE, journalTone(entry))
    }
}
