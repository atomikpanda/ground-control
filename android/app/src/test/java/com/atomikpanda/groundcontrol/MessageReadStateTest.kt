package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.messages.MessageReadState
import com.atomikpanda.groundcontrol.ui.messages.messageReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageReadStateTest {
    private fun human(id: String, at: String) = Message(id = id, role = "human", text = "hi", createdAt = at)
    private fun agent(id: String, at: String) = Message(id = id, role = "agent", text = "ok", createdAt = at)

    @Test fun non_human_message_gets_no_indicator() {
        val a = agent("a", "2026-07-14T10:00:00+00:00")
        val t = Thread(id = "t", messages = listOf(a))
        assertNull(messageReadState(a, t))
    }

    @Test fun sent_when_cursor_absent_or_before_message() {
        val m = human("h", "2026-07-14T10:00:00+00:00")
        assertEquals(MessageReadState.SENT, messageReadState(m, Thread(id = "t", messages = listOf(m), agentSeenAt = null)))
        assertEquals(
            MessageReadState.SENT,
            messageReadState(m, Thread(id = "t", messages = listOf(m), agentSeenAt = "2026-07-14T09:59:00+00:00")),
        )
    }

    @Test fun read_when_cursor_at_or_after_message() {
        val m = human("h", "2026-07-14T10:00:00+00:00")
        val t = Thread(id = "t", messages = listOf(m), agentSeenAt = "2026-07-14T10:00:01+00:00")
        assertEquals(MessageReadState.READ, messageReadState(m, t))
    }

    @Test fun replied_when_an_agent_message_follows() {
        val m = human("h", "2026-07-14T10:00:00+00:00")
        val reply = agent("a", "2026-07-14T10:05:00+00:00")
        // even with a covering cursor, a following agent message reads as REPLIED (the stronger state)
        val t = Thread(id = "t", messages = listOf(m, reply), agentSeenAt = "2026-07-14T10:06:00+00:00")
        assertEquals(MessageReadState.REPLIED, messageReadState(m, t))
    }

    @Test fun read_comparison_is_parsed_not_string_compared() {
        // whole-second cursor vs microsecond message time: a naive lexicographic compare would get this
        // wrong ('+' < '.'), so the helper must parse to instants. Cursor is 0.5s after the message.
        val m = human("h", "2026-07-14T10:00:00.500000+00:00")
        val t = Thread(id = "t", messages = listOf(m), agentSeenAt = "2026-07-14T10:00:01+00:00")
        assertEquals(MessageReadState.READ, messageReadState(m, t))
    }
}
