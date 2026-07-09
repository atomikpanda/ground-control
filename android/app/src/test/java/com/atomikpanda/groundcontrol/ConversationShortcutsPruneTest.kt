package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.notify.ConversationShortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform's dynamic-shortcut cap applies to ALL dynamic shortcuts, not just the
 * conversation-prefixed ones this class manages. [ConversationShortcuts.idsToPrune] must size its
 * pruning against the *whole* existing count, while only ever removing conversation shortcuts
 * (the only ones it owns) and always leaving room for the active thread's shortcut.
 */
class ConversationShortcutsPruneTest {
    private val prefix = "thread_"

    @Test fun no_pruning_needed_when_under_cap() {
        val existing = listOf("thread_a", "thread_b")
        val toPrune = ConversationShortcuts.idsToPrune(existing, prefix, keep = "thread_c", cap = 4)
        assertTrue(toPrune.isEmpty())
    }

    @Test fun prunes_conversation_shortcuts_when_over_cap() {
        val existing = listOf("thread_a", "thread_b", "thread_c")
        // Pushing thread_d would make 4 total against a cap of 3 -> prune 1.
        val toPrune = ConversationShortcuts.idsToPrune(existing, prefix, keep = "thread_d", cap = 3)
        assertEquals(1, toPrune.size)
        assertTrue(toPrune[0].startsWith(prefix))
    }

    @Test fun counts_non_conversation_shortcuts_toward_the_cap() {
        // Two non-conversation (e.g. app-defined) shortcuts already occupy the cap; only one
        // conversation shortcut exists. Cap is 3, so pushing a new conversation shortcut makes 4
        // total and must prune the one conversation shortcut it's allowed to touch.
        val existing = listOf("other_1", "other_2", "thread_a")
        val toPrune = ConversationShortcuts.idsToPrune(existing, prefix, keep = "thread_b", cap = 3)
        assertEquals(listOf("thread_a"), toPrune)
    }

    @Test fun never_prunes_non_conversation_shortcuts_even_if_still_over_cap() {
        // Three non-conversation shortcuts alone already exceed the cap of 2; there are no
        // conversation shortcuts to prune, so the result is empty (best-effort, not a crash).
        val existing = listOf("other_1", "other_2", "other_3")
        val toPrune = ConversationShortcuts.idsToPrune(existing, prefix, keep = "thread_a", cap = 2)
        assertTrue(toPrune.isEmpty())
    }

    @Test fun keep_id_already_present_does_not_double_count_it() {
        // thread_a is being refreshed (already dynamic), so pushing it again doesn't add to the
        // total count. 3 existing against a cap of 3 -> no pruning needed.
        val existing = listOf("thread_a", "thread_b", "other_1")
        val toPrune = ConversationShortcuts.idsToPrune(existing, prefix, keep = "thread_a", cap = 3)
        assertTrue(toPrune.isEmpty())
    }

    @Test fun prunes_multiple_when_far_over_cap() {
        val existing = listOf("thread_a", "thread_b", "thread_c", "thread_d")
        val toPrune = ConversationShortcuts.idsToPrune(existing, prefix, keep = "thread_e", cap = 2)
        assertEquals(3, toPrune.size)
    }
}
