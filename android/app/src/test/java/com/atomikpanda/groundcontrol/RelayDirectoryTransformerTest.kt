package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.InvalidRelayDirectoryException
import com.atomikpanda.groundcontrol.data.RelayDirectoryTransformer
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.HostInfo
import com.atomikpanda.groundcontrol.data.dto.HostsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDirectoryTransformerTest {
    private val transformer = RelayDirectoryTransformer()

    private fun hostInfo(
        hostId: String? = "host-1",
        state: String = "online",
        requestId: String? = null,
        publicUrl: String = "https://host-1.relay.test",
    ) = HostInfo(
        hostId = hostId,
        state = state,
        requestId = requestId,
        publicUrl = publicUrl,
    )

    private fun invalid(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is InvalidRelayDirectoryException)
    }

    @Test fun canonicalizes_each_valid_route_once_and_reuses_result() {
        val calls = mutableListOf<String>()
        val fleet = RelayDirectoryTransformer { raw ->
            calls += raw
            com.atomikpanda.groundcontrol.data.normalizedBaseUrl(raw)
        }.transform(HostsResponse(listOf(hostInfo(publicUrl = "HTTPS://HOST.TEST/root/"))), "relay.test")

        assertEquals(listOf("HTTPS://HOST.TEST/root/"), calls)
        assertEquals("https://host.test/root", fleet.hosts.single().publicUrl)
    }

    @Test fun canonical_equivalent_duplicate_routes_reject_whole_response() {
        invalid {
            transformer.transform(
                HostsResponse(listOf(
                    hostInfo(hostId = "h1", publicUrl = "https://HOST.test/root/"),
                    hostInfo(hostId = "h2", publicUrl = "https://host.test/root"),
                )),
                "relay.test",
            )
        }
    }

    @Test fun duplicate_host_ids_reject_whole_response() {
        invalid {
            transformer.transform(
                HostsResponse(listOf(hostInfo(), hostInfo(publicUrl = "https://other.test"))),
                "relay.test",
            )
        }
    }

    @Test fun supported_pending_states_require_request_id_and_route_but_not_host_id() {
        listOf("pending-approval", "awaiting-enrollment").forEach { state ->
            val pending = hostInfo(
                hostId = null,
                state = state,
                requestId = "request-$state",
                publicUrl = "https://$state.relay.test",
            )
            assertEquals(
                "pending:request-$state",
                transformer.transform(HostsResponse(listOf(pending)), "relay.test").hosts.single().hostId,
            )
        }
        invalid {
            transformer.transform(
                HostsResponse(listOf(hostInfo(hostId = null, state = "pending-approval", publicUrl = "https://pending.test"))),
                "relay.test",
            )
        }
    }

    @Test fun duplicate_pending_request_identities_reject_whole_response() {
        invalid {
            transformer.transform(
                HostsResponse(listOf(
                    hostInfo(hostId = null, state = "pending-approval", requestId = "request-1", publicUrl = "https://a.test"),
                    hostInfo(hostId = null, state = "awaiting-enrollment", requestId = "request-1", publicUrl = "https://b.test"),
                )),
                "relay.test",
            )
        }
    }

    @Test fun non_pending_missing_or_blank_host_ids_reject_whole_response() {
        listOf(null, "", "  ").forEach { hostId ->
            invalid {
                transformer.transform(
                    HostsResponse(listOf(hostInfo(hostId = hostId, state = "offline", requestId = "request-1"))),
                    "relay.test",
                )
            }
        }
    }

    @Test fun every_row_requires_an_unpadded_usable_route() {
        listOf("", "   ", " https://padded.test", "https://padded.test ", "ftp://host.test", "not a url").forEach { route ->
            invalid { transformer.transform(HostsResponse(listOf(hostInfo(publicUrl = route))), "relay.test") }
        }
    }

    @Test fun later_invalid_entry_rejects_the_complete_fleet() {
        invalid {
            transformer.transform(
                HostsResponse(listOf(
                    hostInfo(hostId = "valid", publicUrl = "https://valid.test"),
                    hostInfo(hostId = "broken", publicUrl = "https://broken.test "),
                )),
                "relay.test",
            )
        }
    }

    @Test fun frozen_valid_wire_fixtures_produce_exact_domain_fleet() {
        val response = buildJson().decodeFromString<HostsResponse>(FROZEN_VALID_DIRECTORY_JSON)

        val actual = transformer.transform(response, "relay.test").hosts

        assertEquals("host-a", actual[0].hostId)
        assertEquals("https://host-a.relay.test/root", actual[0].publicUrl)
        assertEquals("host-b", actual[1].hostId)
        assertEquals("http://192.168.1.9:47190", actual[1].publicUrl)
        assertEquals("pending:request-1", actual[2].hostId)
        assertEquals("https://pending.relay.test", actual[2].publicUrl)
    }

    @Test fun frozen_pending_wire_row_without_route_remains_an_invalid_boundary_fixture() {
        val response = buildJson().decodeFromString<HostsResponse>(FROZEN_PENDING_WITHOUT_ROUTE_JSON)

        invalid { transformer.transform(response, "relay.test") }
    }

    private companion object {
        const val FROZEN_VALID_DIRECTORY_JSON = """{
            "hosts":[
              {"host_id":"host-a","state":"online","public_url":"HTTPS://HOST-A.RELAY.TEST/root/","refresh":"refresh-a"},
              {"host_id":"host-b","state":"online","public_url":"http://192.168.1.9:47190/"},
              {"state":"pending-approval","request_id":"request-1","public_url":"https://pending.relay.test"}
            ]
        }"""
        const val FROZEN_PENDING_WITHOUT_ROUTE_JSON = """{
            "hosts":[{"state":"pending-approval","request_id":"request-1"}]
        }"""
    }
}
