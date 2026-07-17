package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import com.atomikpanda.groundcontrol.data.upsertConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionsCodecTest {
    @Test fun round_trips_a_connection_list() {
        val list = listOf(
            WorkspaceConnection("1", "http://host:47100", "tok", "ws-a"),
            WorkspaceConnection("2", "http://other:47100", null, "ws-b"),
        )
        val restored = ConnectionsCodec.decode(ConnectionsCodec.encode(list))
        assertEquals(list, restored)
    }

    @Test fun decode_of_blank_is_empty() {
        assertEquals(emptyList<WorkspaceConnection>(), ConnectionsCodec.decode(""))
        assertEquals(emptyList<WorkspaceConnection>(), ConnectionsCodec.decode("not json"))
    }

    @Test fun normalizes_base_url_trailing_slash_and_validates() {
        assertEquals("http://h:47100", normalizedBaseUrl(" http://h:47100/ "))
        assertEquals("https://h", normalizedBaseUrl("https://h"))
        assertNull(normalizedBaseUrl("notaurl"))        // no scheme
        assertNull(normalizedBaseUrl(""))
    }

    // ── upsertConnection tests ──────────────────────────────────────────────

    @Test fun upsert_same_baseUrl_different_id_keeps_size_1_with_new_token() {
        val existing = listOf(
            WorkspaceConnection("old-id", "http://host:47100", "old-token", "ws-a")
        )
        val incoming = WorkspaceConnection("new-id", "http://host:47100", "new-token", "ws-a")
        val result = upsertConnection(existing, incoming)
        assertEquals(1, result.size)
        assertEquals("new-token", result[0].token)
        assertEquals("new-id", result[0].id)
    }

    @Test fun upsert_genuinely_new_baseUrl_grows_list() {
        val existing = listOf(
            WorkspaceConnection("id-1", "http://host-a:47100", "tok-a", "ws-a")
        )
        val incoming = WorkspaceConnection("id-2", "http://host-b:47100", "tok-b", "ws-b")
        val result = upsertConnection(existing, incoming)
        assertEquals(2, result.size)
    }

    @Test fun upsert_same_id_replaces_entry() {
        val existing = listOf(
            WorkspaceConnection("id-1", "http://host:47100", "old-token", "ws-old")
        )
        val incoming = WorkspaceConnection("id-1", "http://host:47100", "new-token", "ws-new")
        val result = upsertConnection(existing, incoming)
        assertEquals(1, result.size)
        assertEquals("new-token", result[0].token)
        assertEquals("ws-new", result[0].workspaceName)
    }

    @Test fun round_trips_color_and_glyph_overrides() {
        val list = listOf(
            WorkspaceConnection("1", "http://h:47100", "tok", "ws-a",
                colorOverride = "#FF1976D2", glyphOverride = "Z"),
        )
        val restored = ConnectionsCodec.decode(ConnectionsCodec.encode(list))
        assertEquals(list, restored)
        assertEquals("#FF1976D2", restored[0].colorOverride)
        assertEquals("Z", restored[0].glyphOverride)
    }

    @Test fun decodes_legacy_json_without_override_fields() {
        val legacy = """[{"id":"1","baseUrl":"http://h:47100","token":"tok","workspaceName":"ws-a"}]"""
        val restored = ConnectionsCodec.decode(legacy)
        assertEquals(1, restored.size)
        assertNull(restored[0].colorOverride)
        assertNull(restored[0].glyphOverride)
    }
}
