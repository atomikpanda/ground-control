package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
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
}
