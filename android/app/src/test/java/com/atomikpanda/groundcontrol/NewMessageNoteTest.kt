package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.ui.home.notesFrom
import org.junit.Assert.assertEquals
import org.junit.Test

class NewMessageNoteTest {
    private val conn = WorkspaceConnection("c1", "http://h", "tok", "ws")

    @Test fun notes_are_unseen_and_not_needs_you() {
        val threads = listOf(
            ThreadSummary(id = "t1", subject = "plain unread", unseen = true),
            ThreadSummary(id = "t2", subject = "needs you", needsYou = true, unseen = true), // action card, not a note
            ThreadSummary(id = "t3", subject = "seen", unseen = false),
        )
        val notes = notesFrom(conn, threads)
        assertEquals(1, notes.size)
        assertEquals("t1", notes[0].threadId)
    }
}
