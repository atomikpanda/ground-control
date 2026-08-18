package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #471: the phone stores a relay ACCOUNT (domain + per-device fleet token), never a
 * VM address. The account arrives on its own deep link, beside — not instead of —
 * the per-workspace pairing link, which still has to work for LAN/tailnet setup.
 */
class RelayAccountTest {
    @Test fun parses_relay_account_link() {
        val a = PairLink.parseRelay("groundcontrol://add-relay?relay=relay.example.com&token=fleet-abc")
        assertNotNull(a)
        assertEquals("relay.example.com", a!!.relayDomain)
        assertEquals("fleet-abc", a.fleetToken)
    }

    @Test fun round_trips_an_encoded_token() {
        // The producer percent-encodes with quote(): space→%20, +→%2B, /→%2F, =→%3D.
        val a = PairLink.parseRelay(
            "groundcontrol://add-relay?relay=relay.example.com&token=tok%20en%2F%2B%3D"
        )!!
        assertEquals("tok en/+=", a.fleetToken)
    }

    @Test fun returns_null_on_a_missing_param() {
        assertNull(PairLink.parseRelay("groundcontrol://add-relay?relay=relay.example.com"))
        assertNull(PairLink.parseRelay("groundcontrol://add-relay?token=fleet-abc"))
        assertNull(PairLink.parseRelay("groundcontrol://add-relay?relay=&token=fleet-abc"))
        assertNull(PairLink.parseRelay("groundcontrol://add-relay"))
    }

    @Test fun returns_null_on_the_wrong_scheme_or_host() {
        assertNull(PairLink.parseRelay("https://add-relay?relay=r&token=t"))
        // The per-workspace pairing link is NOT a relay account.
        assertNull(PairLink.parseRelay("groundcontrol://add?url=http%3A%2F%2Fh%3A1&token=t"))
    }

    @Test fun the_pairing_link_contract_is_untouched() {
        // QR/manual pairing keeps working: a valid link parses, a missing `url` is null.
        val c = PairLink.parse("groundcontrol://add?url=http%3A%2F%2F192.168.1.50%3A47100&token=t&workspace=home")
        assertNotNull(c)
        assertEquals("http://192.168.1.50:47100", c!!.baseUrl)
        assertNull(PairLink.parse("groundcontrol://add?token=abc"))
        // ...and an add-relay link is not a workspace connection.
        assertNull(PairLink.parse("groundcontrol://add-relay?relay=r&token=t"))
    }
}
