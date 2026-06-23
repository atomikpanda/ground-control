package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadDtosTest {
    private val json = buildJson()

    @Test fun parses_thread_summary() {
        val raw = """{"id":"t1","subject":"Idea","updated_at":"2026-06-23T00:00:00Z",
            "awaiting_reply":true,"last_message":"build a thing","message_count":1}"""
        val t = json.decodeFromString(ThreadSummary.serializer(), raw.trimIndent())
        assertEquals("t1", t.id)
        assertEquals("Idea", t.subject)
        assertTrue(t.awaitingReply)
        assertEquals("build a thing", t.lastMessage)
    }

    @Test fun parses_full_thread_with_messages() {
        val raw = """{"id":"t1","subject":"Idea","created_at":"x","updated_at":"y","task_slug":null,
            "awaiting_reply":false,
            "messages":[{"id":"m1","thread_id":"t1","role":"human","text":"hi","created_at":"x"},
                        {"id":"m2","thread_id":"t1","role":"agent","text":"drafted","created_at":"y"}]}"""
        val t = json.decodeFromString(Thread.serializer(), raw.trimIndent())
        assertEquals(2, t.messages.size)
        assertEquals("agent", t.messages[1].role)
        assertEquals(false, t.awaitingReply)
    }
}
