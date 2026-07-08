package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun parses_thread_with_spec_id() {
        val raw = """{"id":"t1","subject":"Idea","spec_id":"s1","messages":[]}"""
        val t = json.decodeFromString(Thread.serializer(), raw)
        assertEquals("s1", t.specId)
    }

    @Test fun parses_thread_without_spec_id_gives_null() {
        val raw = """{"id":"t1","subject":"Idea","messages":[]}"""
        val t = json.decodeFromString(Thread.serializer(), raw)
        assertEquals(null, t.specId)
    }

    @Test fun parses_needs_you_and_unseen() {
        val json = """{"id":"t1","subject":"s","needs_you":true,"unseen":true}"""
        val s = buildJson().decodeFromString<ThreadSummary>(json)
        assertTrue(s.needsYou)
        assertTrue(s.unseen)
    }

    @Test fun needs_you_and_unseen_default_false_when_omitted() {
        val s = buildJson().decodeFromString<ThreadSummary>("""{"id":"t1","subject":"s"}""")
        assertFalse(s.needsYou)
        assertFalse(s.unseen)
    }

    @Test fun parses_message_with_decision_payload() {
        val raw = """{"id":"m1","thread_id":"t1","role":"agent","text":"pick one","kind":"decision",
            "decision":{"options":["File-per-thread","SQLite"],"recommended":0,"allow_free_text":false}}"""
        val m = json.decodeFromString(Message.serializer(), raw.trimIndent())
        assertEquals("decision", m.kind)
        assertEquals(listOf("File-per-thread", "SQLite"), m.decision?.options)
        assertEquals(0, m.decision?.recommended)
        assertFalse(m.decision!!.allowFreeText)
    }

    @Test fun message_kind_and_decision_default_when_omitted() {
        val raw = """{"id":"m1","thread_id":"t1","role":"human","text":"hi"}"""
        val m = json.decodeFromString(Message.serializer(), raw)
        assertEquals("note", m.kind)
        assertNull(m.decision)
    }

    @Test fun parses_decision_multi_true() {
        val raw = """{"id":"m1","thread_id":"t1","role":"agent","text":"pick some","kind":"decision",
            "decision":{"options":["a","b","c"],"multi":true}}"""
        val m = json.decodeFromString(Message.serializer(), raw.trimIndent())
        assertTrue(m.decision!!.multi)
    }

    @Test fun decision_multi_defaults_false_when_omitted() {
        val raw = """{"id":"m1","thread_id":"t1","role":"agent","text":"pick one","kind":"decision",
            "decision":{"options":["a","b"]}}"""
        val m = json.decodeFromString(Message.serializer(), raw.trimIndent())
        assertFalse(m.decision!!.multi)
    }

    @Test fun parses_needs_decision() {
        val raw = """{"id":"t1","subject":"s","needs_decision":true}"""
        val s = buildJson().decodeFromString<ThreadSummary>(raw)
        assertTrue(s.needsDecision)
    }

    @Test fun needs_decision_defaults_false_when_omitted() {
        val s = buildJson().decodeFromString<ThreadSummary>("""{"id":"t1","subject":"s"}""")
        assertFalse(s.needsDecision)
    }
}
