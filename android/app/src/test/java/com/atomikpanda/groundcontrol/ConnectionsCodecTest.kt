package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionsCodec
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.applyIdentityOverride
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import com.atomikpanda.groundcontrol.data.replaceHostConnections
import com.atomikpanda.groundcontrol.data.upsertConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.LegacyRouteOwnership
import com.atomikpanda.groundcontrol.data.legacyRouteOwnership
import com.atomikpanda.groundcontrol.data.agreesWith
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

    @Test fun round_trips_and_resolves_retired_connection_ids() {
        val current = WorkspaceConnection(
            id = "current",
            baseUrl = "https://relay/workspaces/ws",
            legacyConnectionIds = listOf("retired"),
        )
        val restored = ConnectionsCodec.decode(ConnectionsCodec.encode(listOf(current)))

        val exact = WorkspaceConnection("retired", "https://other/workspaces/ws")

        assertEquals(exact, listOf(current, exact).findByConnectionId("retired"))
        assertEquals(current, restored.findByConnectionId("retired"))
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

    @Test fun normalizes_only_case_insensitive_url_components() {
        assertEquals(
            "https://User:RouteToken@old.relay.example/CaseSensitive",
            normalizedBaseUrl("HTTPS://User:RouteToken@OLD.RELAY.EXAMPLE/CaseSensitive"),
        )
        assertEquals(
            "https://old.relay.example/casesensitive",
            normalizedBaseUrl("https://OLD.RELAY.EXAMPLE/casesensitive"),
        )
        assertNull(normalizedBaseUrl("HOST-STABLE"))
    }

    @Test fun rejects_base_url_with_query_or_fragment() {
        // Endpoints are appended as path segments; a query/fragment would
        // swallow them (".../workspaces" lands inside the query string).
        assertNull(normalizedBaseUrl("https://h:47190?profile=dev"))
        assertNull(normalizedBaseUrl("https://h:47190/#top"))
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

    @Test fun upsert_same_baseUrl_different_verified_identities_keeps_both() {
        val existing = listOf(
            WorkspaceConnection(
                id = "host-a-local",
                baseUrl = "https://shared/workspaces/ws",
                hostId = "host-a",
                workspaceId = "ws",
            ),
        )
        val incoming = WorkspaceConnection(
            id = "host-b-local",
            baseUrl = "https://shared/workspaces/ws",
            hostId = "host-b",
            workspaceId = "ws",
        )

        assertEquals(2, upsertConnection(existing, incoming).size)
    }

    @Test fun upsert_does_not_adopt_an_unverified_manual_row_by_baseUrl() {
        val manual = WorkspaceConnection(
            id = "manual",
            baseUrl = "https://shared/workspaces/ws",
            hostId = "https://shared",
            workspaceId = "ws",
        )
        val discovered = WorkspaceConnection(
            id = "derived",
            baseUrl = manual.baseUrl,
            hostId = "host-a",
            workspaceId = "ws",
        )

        assertEquals(listOf(manual, discovered), upsertConnection(listOf(manual), discovered))
    }

    @Test fun upsert_genuinely_new_baseUrl_grows_list() {
        val existing = listOf(
            WorkspaceConnection("id-1", "http://host-a:47100", "tok-a", "ws-a")
        )
        val incoming = WorkspaceConnection("id-2", "http://host-b:47100", "tok-b", "ws-b")
        val result = upsertConnection(existing, incoming)
        assertEquals(2, result.size)
    }


    @Test fun host_refresh_replaces_the_authoritative_workspace_set() {
        val current = listOf(
            WorkspaceConnection(
                id = "kept",
                baseUrl = "https://old/workspaces/a",
                workspaceName = "a",
                colorOverride = "#FF1976D2",
                hostId = "host-a",
                workspaceId = "a",
            ),
            WorkspaceConnection(
                id = "stale",
                baseUrl = "https://old/workspaces/deleted",
                hostId = "host-a",
                workspaceId = "deleted",
            ),
            WorkspaceConnection(
                id = "other-host",
                baseUrl = "https://other/workspaces/a",
                hostId = "host-b",
                workspaceId = "a",
            ),
            WorkspaceConnection(id = "manual", baseUrl = "http://lan"),
        )
        val discovered = listOf(
            WorkspaceConnection(
                id = "new-derived-id",
                baseUrl = "https://new/workspaces/a",
                workspaceName = "a",
                hostId = "host-a",
                workspaceId = "a",
            ),
        )

        val replaced = replaceHostConnections(
            existing = current,
            hostId = "host-a",
            discovered = discovered,
            identities = emptyList(),
            hosts = listOf(HostConnection(hostId = "host-a", publicUrl = "https://old")),
        )

        assertEquals(listOf("other-host", "manual", "kept"), replaced.map { it.id })
        assertEquals("https://new/workspaces/a", replaced.last().baseUrl)
        assertEquals("#FF1976D2", replaced.last().colorOverride)
    }

    @Test fun host_refresh_removes_a_missing_legacy_workspace_suffix() {
        val stale = WorkspaceConnection(
            id = "legacy-stale",
            baseUrl = "https://old/workspaces/deleted",
            hostId = "host-a",
            workspaceId = null,
        )
        val discovered = WorkspaceConnection(
            id = "live",
            baseUrl = "https://current/workspaces/live",
            hostId = "host-a",
            workspaceId = "live",
        )

        val replaced = replaceHostConnections(
            existing = listOf(stale),
            hostId = "host-a",
            discovered = listOf(discovered),
            identities = emptyList(),
            hosts = listOf(HostConnection(hostId = "host-a", publicUrl = "https://old")),
        )

        assertEquals(listOf("live"), replaced.map { it.workspaceId })
    }
    @Test fun host_refresh_removes_a_uniquely_owned_url_valued_legacy_suffix() {
        val oldBase = "https://old.relay"
        val host = HostConnection(
            hostId = "host-a",
            publicUrl = "https://current.relay",
            legacyPublicUrls = listOf(oldBase),
        )
        val stale = WorkspaceConnection(
            id = "legacy-stale",
            baseUrl = "$oldBase/workspaces/deleted",
            hostId = oldBase,
        )
        val live = WorkspaceConnection(
            id = "live",
            baseUrl = "${host.publicUrl}/workspaces/live",
            hostId = host.hostId,
            workspaceId = "live",
        )

        val replaced = replaceHostConnections(
            existing = listOf(stale),
            hostId = host.hostId,
            discovered = listOf(live),
            identities = emptyList(),
            hosts = listOf(host),
        )

        assertEquals(listOf("live"), replaced.map { it.id })
    }

    @Test fun host_refresh_removes_a_uniquely_owned_null_host_legacy_suffix() {
        val oldBase = "https://old.relay"
        val host = HostConnection(
            hostId = "host-a",
            publicUrl = "https://current.relay",
            legacyPublicUrls = listOf(oldBase),
        )
        val stale = WorkspaceConnection(
            id = "legacy-stale",
            baseUrl = "$oldBase/workspaces/deleted",
            hostId = null,
        )

        val replaced = replaceHostConnections(
            existing = listOf(stale),
            hostId = host.hostId,
            discovered = emptyList(),
            identities = emptyList(),
            hosts = listOf(host),
        )

        assertEquals(emptyList<WorkspaceConnection>(), replaced)
    }

    @Test fun host_refresh_preserves_unmatched_ambiguous_and_root_legacy_rows() {
        val sharedBase = "https://shared.relay"
        val host = HostConnection(
            hostId = "host-a",
            publicUrl = "https://a.relay",
            legacyPublicUrls = listOf(sharedBase),
        )
        val otherHost = HostConnection(
            hostId = "host-b",
            publicUrl = "https://b.relay",
            legacyPublicUrls = listOf(sharedBase),
        )
        val unmatched = WorkspaceConnection(
            id = "unmatched",
            baseUrl = "https://unknown.relay/workspaces/deleted",
        )
        val ambiguous = WorkspaceConnection(
            id = "ambiguous",
            baseUrl = "$sharedBase/workspaces/deleted",
        )
        val root = WorkspaceConnection(
            id = "root",
            baseUrl = "https://a.relay",
        )

        val replaced = replaceHostConnections(
            existing = listOf(unmatched, ambiguous, root),
            hostId = host.hostId,
            discovered = emptyList(),
            identities = emptyList(),
            hosts = listOf(host, otherHost),
        )

        assertEquals(listOf("unmatched", "ambiguous", "root"), replaced.map { it.id })
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

    @Test fun manual_re_pair_with_a_blank_token_clears_active_and_direct_credentials() {
        val existing = listOf(
            WorkspaceConnection(
                id = "id-1",
                baseUrl = "http://host:47100",
                token = "old-token",
                workspaceName = "ws",
                directToken = "hidden-direct-token",
            ),
        )
        val incoming = WorkspaceConnection(
            id = "id-1",
            baseUrl = "http://host:47100",
            token = null,
            workspaceName = "ws",
        )

        val result = upsertConnection(
            existing,
            incoming,
            preservePriorDirectToken = false,
        ).single()

        assertNull(result.token)
        assertNull(result.directToken)
    }

    @Test fun direct_host_rediscovery_without_a_token_reactivates_the_preserved_credential() {
        val existing = listOf(
            WorkspaceConnection(
                id = "local-id",
                baseUrl = "http://direct/workspaces/ws",
                hostId = "host-a",
                workspaceId = "ws",
                directToken = "direct-token",
            ),
        )
        val incoming = WorkspaceConnection(
            id = "derived-id",
            baseUrl = "http://direct/workspaces/ws",
            hostId = "host-a",
            workspaceId = "ws",
        )

        val result = upsertConnection(
            existing,
            incoming,
            activatePriorDirectToken = true,
        ).single()

        assertEquals("direct-token", result.token)
        assertEquals("direct-token", result.directToken)
    }

    @Test fun relay_host_rediscovery_preserves_but_does_not_activate_the_direct_credential() {
        val existing = listOf(
            WorkspaceConnection(
                id = "local-id",
                baseUrl = "http://direct/workspaces/ws",
                token = "direct-token",
                hostId = "host-a",
                workspaceId = "ws",
                directToken = "direct-token",
            ),
        )
        val incoming = WorkspaceConnection(
            id = "derived-id",
            baseUrl = "https://relay/workspaces/ws",
            hostId = "host-a",
            workspaceId = "ws",
        )

        val result = upsertConnection(existing, incoming).single()

        assertNull(result.token)
        assertEquals("direct-token", result.directToken)
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

    @Test fun upsert_preserves_prior_override_when_incoming_omits_it() {
        val existing = listOf(
            WorkspaceConnection("id-1", "http://host:47100", "old", "ws",
                colorOverride = "#FF7B1FA2", glyphOverride = "Q"))
        val incoming = WorkspaceConnection("id-1", "http://host:47100", "new", "ws")
        val result = upsertConnection(existing, incoming)
        assertEquals(1, result.size)
        assertEquals("new", result[0].token)
        assertEquals("#FF7B1FA2", result[0].colorOverride)   // preserved
        assertEquals("Q", result[0].glyphOverride)           // preserved
    }

    @Test fun upsert_preserves_override_when_matched_by_baseUrl_after_id_change() {
        val existing = listOf(
            WorkspaceConnection("old-id", "http://host:47100", "old", "ws",
                colorOverride = "#FF00796B", glyphOverride = null))
        val incoming = WorkspaceConnection("new-id", "http://host:47100", "new", "ws")
        val result = upsertConnection(existing, incoming)
        assertEquals(1, result.size)
        assertEquals("new-id", result[0].id)
        assertEquals("#FF00796B", result[0].colorOverride)   // carried onto the replacement
    }

    @Test fun upsert_lets_an_explicit_incoming_override_win() {
        val existing = listOf(
            WorkspaceConnection("id-1", "http://host:47100", "t", "ws", colorOverride = "#FFAAAAAA"))
        val incoming = existing[0].copy(colorOverride = "#FF111111")
        assertEquals("#FF111111", upsertConnection(existing, incoming)[0].colorOverride)
    }

    @Test fun apply_identity_override_replaces_only_the_target_and_can_clear() {
        val list = listOf(
            WorkspaceConnection("a", "http://a", null, "ws-a", colorOverride = "#FF1976D2"),
            WorkspaceConnection("b", "http://b", null, "ws-b"))
        val set = applyIdentityOverride(list, "b", "#FFD32F2F", "B")
        assertEquals("#FFD32F2F", set.first { it.id == "b" }.colorOverride)
        assertEquals("#FF1976D2", set.first { it.id == "a" }.colorOverride)   // untouched
        val cleared = applyIdentityOverride(set, "a", null, null)             // reset to auto
        assertNull(cleared.first { it.id == "a" }.colorOverride)
    }
    @Test fun legacy_route_ownership_preserves_pathful_host_root() {
        val host = HostConnection(hostId = "h1", publicUrl = "https://relay.test/gc")
        val connection = WorkspaceConnection("legacy", "https://relay.test/gc/workspaces/ws-1")
        assertEquals(
            LegacyRouteOwnership.Owned("h1", "https://relay.test/gc", "ws-1"),
            legacyRouteOwnership(connection, listOf(host)),
        )
    }

    @Test fun legacy_route_ownership_rejects_partial_unknown_suffix_and_encoded_dot_routes() {
        val host = HostConnection(hostId = "h1", publicUrl = "https://relay.test/gc")
        listOf(
            "https://relay.test/gc-admin/workspaces/ws-1",
            "https://relay.test/gc/workspaces/ws-1/admin",
            "https://relay.test/gc/workspaces/a%2Fb",
            "https://relay.test/gc/workspaces/%2e",
            "https://relay.test/gc/workspaces/%2E",
            "https://relay.test/gc/workspaces/%2e%2e",
            "https://relay.test/gc/workspaces/.%2e",
        ).forEach { base ->
            assertEquals(
                LegacyRouteOwnership.Unknown,
                legacyRouteOwnership(WorkspaceConnection("legacy", base), listOf(host)),
            )
        }
    }

    @Test fun legacy_route_ownership_reports_multiple_valid_claims() {
        val hosts = listOf(
            HostConnection(hostId = "h1", publicUrl = "https://relay.test/gc"),
            HostConnection(hostId = "h2", publicUrl = "https://relay.test/gc"),
        )
        assertEquals(
            LegacyRouteOwnership.Ambiguous,
            legacyRouteOwnership(
                WorkspaceConnection("legacy", "https://relay.test/gc/workspaces/ws-1"),
                hosts,
            ),
        )
    }

    @Test fun legacy_route_ownership_accepts_historical_and_direct_identities() {
        val relayHost = HostConnection(
            hostId = "h1",
            directUrl = "https://direct.test/root",
            publicUrl = "https://current.test/root",
            relayDomain = "current.test",
            legacyPublicUrls = listOf("https://old.test/root"),
        )
        assertEquals(
            LegacyRouteOwnership.Owned("h1", "https://old.test/root", "ws-1"),
            legacyRouteOwnership(
                WorkspaceConnection("legacy", "https://old.test/root/workspaces/ws-1"),
                listOf(relayHost),
            ),
        )
        assertEquals(
            LegacyRouteOwnership.Owned("h1", "https://direct.test/root", "ws-1"),
            legacyRouteOwnership(
                WorkspaceConnection("legacy", "https://direct.test/root/workspaces/ws-1"),
                listOf(relayHost),
            ),
        )
    }

    @Test fun parser_ignores_stored_ids_without_candidate_evidence() {
        val connection = WorkspaceConnection(
            id = "legacy",
            baseUrl = "https://known.test/root/workspaces/ws-1",
            hostId = "h1",
            workspaceId = "ws-1",
        )
        assertEquals(LegacyRouteOwnership.Unknown, legacyRouteOwnership(connection, emptyList()))
    }
    @Test fun pre_ownership_json_remains_readable_and_has_current_route_evidence() {
        val raw = """[{"id":"legacy","baseUrl":"https://host1.test/root/workspaces/ws-1","token":"standing-secret"}]"""
        val connection = ConnectionsCodec.decode(raw).single()
        val host = HostConnection(hostId = "host1", publicUrl = "https://host1.test/root")

        assertEquals(
            WorkspaceConnection(
                id = "legacy",
                baseUrl = "https://host1.test/root/workspaces/ws-1",
                token = "standing-secret",
            ),
            connection,
        )
        assertEquals(
            LegacyRouteOwnership.Owned("host1", "https://host1.test/root", "ws-1"),
            legacyRouteOwnership(connection, listOf(host)),
        )
    }

    @Test fun URL_valued_stored_host_id_must_not_resolve_to_a_different_candidate() {
        val hostA = HostConnection(hostId = "host-a", publicUrl = "https://host-a.test/root")
        val hostB = HostConnection(hostId = "host-b", publicUrl = "https://host-b.test/root")
        val conflicting = WorkspaceConnection(
            id = "legacy",
            baseUrl = "https://host-a.test/root/workspaces/ws-1",
            hostId = hostB.publicUrl,
        )
        val evidence = legacyRouteOwnership(conflicting, listOf(hostA, hostB))
            as LegacyRouteOwnership.Owned

        assertNull(evidence.takeIf { conflicting.agreesWith(it, listOf(hostA, hostB)) })
        assertEquals(
            evidence,
            evidence.takeIf {
                conflicting.copy(hostId = hostA.publicUrl).agreesWith(it, listOf(hostA, hostB))
            },
        )
    }
}
