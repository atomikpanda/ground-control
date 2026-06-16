package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    @Test fun parses_valid_link() {
        val c = PairLink.parse("groundcontrol://add?url=https%3A%2F%2Fws.relay.example.com&token=abc&workspace=ws")
        assertNotNull(c)
        assertEquals("https://ws.relay.example.com", c!!.baseUrl)
        assertEquals("abc", c.token)
        assertEquals("ws", c.workspaceName)
    }

    @Test fun rejects_wrong_scheme_or_missing_fields() {
        assertNull(PairLink.parse("https://add?url=x"))
        assertNull(PairLink.parse("groundcontrol://add?token=abc"))   // no url
    }

    @Test fun decodes_exact_token_from_mship_encoding() {
        // mship build_pair_link(..., token="tok en/+=") emits token=tok%20en%2F%2B%3D
        val c = PairLink.parse("groundcontrol://add?url=https%3A%2F%2Fws.example.com&token=tok%20en%2F%2B%3D&workspace=ws")!!
        assertEquals("tok en/+=", c.token)
        assertEquals("https://ws.example.com", c.baseUrl)
    }
}
