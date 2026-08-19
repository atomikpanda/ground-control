package com.atomikpanda.groundcontrol.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

@Serializable
data class WorkspaceConnection(
    val id: String,
    val baseUrl: String,
    val token: String? = null,
    val workspaceName: String = "",
    /** Operator override for the identity badge color, "#AARRGGBB"; null = auto-derived. */
    val colorOverride: String? = null,
    /** Operator override for the identity badge glyph; null = auto (name's first letter). */
    val glyphOverride: String? = null,
    /** Host this connection was discovered on (#472); null for manually paired
     *  connections and for JSON persisted before this field existed — declared
     *  with a default so old stored lists still deserialize (missing keys are
     *  NOT covered by ignoreUnknownKeys, only defaults cover them). */
    val hostId: String? = null,
    /** Last-known discovery state from the host registry (#472); null = unknown/manual. */
    val state: String? = null,
    /** SERVER workspace id on [hostId]; null for manually paired rows. With
     *  [hostId] it is the identity tuple everything re-keys on (#471). */
    val workspaceId: String? = null,
    /** Base URLs this row used to answer to. [baseUrl] is DERIVED from the host,
     *  so adoption (and any host move) rewrites it — and every notification
     *  PendingIntent already sitting in the shade carries the OLD one. Deep-link
     *  resolution consults these so those taps keep landing. */
    val legacyBaseUrls: List<String> = emptyList(),
)

/** Short URL-safe fingerprint of a host handle. Connection ids are interpolated
 *  raw into nav routes, so host scoping can't embed the handle itself. */
private fun hostFingerprint(handle: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(handle.trimEnd('/').toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }

/**
 * Derive a connection from a host's discovered workspace (#472): the connection
 * id is the SERVER workspace id scoped by a host fingerprint — deterministic, so
 * re-discovery matches in [upsertConnection] and identity overrides carry
 * forward, and host-scoped because the same logical workspace may exist on
 * several hosts. The baseUrl is the workspace-addressed prefix — opaque to
 * everything downstream.
 */
fun deriveConnection(
    hostBase: String,
    hostToken: String?,
    hostId: String,
    workspaceId: String,
    workspaceName: String,
    state: String,
): WorkspaceConnection = WorkspaceConnection(
    // Scoped by the host HANDLE, not its URL: a host that moves subdomains (or
    // onto a LAN address) is the same host, and a changed row id would orphan
    // every nav route and notification already issued for it.
    id = workspaceId + "-" + hostFingerprint(hostId),
    baseUrl = workspaceBaseUrl(hostBase, workspaceId),
    token = hostToken,
    workspaceName = workspaceName,
    hostId = hostId,
    state = state,
    workspaceId = workspaceId,
)

/**
 * The one place a workspace's base URL is built (#471): it is DERIVED from
 * (host base, workspace id), never stored as an independent address. It stays
 * the single opaque prefix every `SpecApi` call site concatenates onto, so
 * moving a host between the relay and a LAN URL touches no call site.
 */
fun workspaceBaseUrl(hostBase: String, workspaceId: String): String =
    hostBase.trimEnd('/') + "/workspaces/" + workspaceId

/** Derive a connection from a host and one of its discovered workspaces. No
 *  token: relay-borne traffic is authorized by the short-lived bearer the
 *  refresh interceptor mints from [HostConnection.refresh] (AC9). */
fun deriveConnection(host: HostConnection, info: com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo): WorkspaceConnection =
    deriveConnection(
        hostBase = host.hostBase(),
        hostToken = null,
        hostId = host.hostId,
        workspaceId = info.id,
        workspaceName = info.name,
        state = info.state,
    )

/** Pure (de)serialization of the connection list stored in DataStore. */
object ConnectionsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WorkspaceConnection.serializer())

    fun encode(list: List<WorkspaceConnection>): String = json.encodeToString(serializer, list)

    fun decode(raw: String): List<WorkspaceConnection> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

/** A verified host/workspace tuple, excluding #472's URL-valued legacy handle. */
fun WorkspaceConnection.hasStableIdentityTuple(): Boolean =
    !hostId.isNullOrBlank() &&
        !workspaceId.isNullOrBlank() &&
        !hostId.startsWith("http://") &&
        !hostId.startsWith("https://")

/** The same verified workspace on the same host, whatever URL it answers on. */
private fun sameWorkspace(a: WorkspaceConnection, b: WorkspaceConnection): Boolean =
    a.hasStableIdentityTuple() &&
        b.hasStableIdentityTuple() &&
        a.hostId == b.hostId &&
        a.workspaceId == b.workspaceId

/**
 * Pure upsert, keyed on the (hostId, workspaceId) identity tuple — a host that
 * re-registers on a new subdomain rewrites the derived [WorkspaceConnection.baseUrl],
 * so the URL can no longer be a key. The id/baseUrl match is retained as a
 * fallback for manually paired rows, which have no tuple.
 *
 * Re-discovery carries forward a prior entry's colorOverride/glyphOverride when
 * [conn] omits them, so it never silently resets a customized identity (ac5); an
 * explicit override on [conn] still wins. Previously-used base URLs are
 * accumulated so already-issued notification intents keep resolving.
 */
fun upsertConnection(
    existing: List<WorkspaceConnection>,
    conn: WorkspaceConnection,
): List<WorkspaceConnection> {
    val matches: (WorkspaceConnection) -> Boolean = { prior ->
        sameWorkspace(prior, conn) ||
            (
                !prior.hasStableIdentityTuple() &&
                    !conn.hasStableIdentityTuple() &&
                    (prior.id == conn.id || prior.baseUrl == conn.baseUrl)
            )
    }
    val prior = existing.firstOrNull(matches)
    val merged = conn.copy(
        // On an IDENTITY match the row id is the phone's local handle (nav
        // routes, notification ids) and survives — otherwise the next directory
        // read would re-mint it and undo an adoption. A legacy URL/id match is a
        // manual re-pair, where the incoming id is the operator's new one.
        id = if (prior != null && sameWorkspace(prior, conn)) prior.id else conn.id,
        colorOverride = conn.colorOverride ?: prior?.colorOverride,
        glyphOverride = conn.glyphOverride ?: prior?.glyphOverride,
        legacyBaseUrls = carryLegacyUrls(prior, conn),
    )
    return existing.filterNot(matches) + merged
}

/** Every base URL this row has answered on before its current one. */
private fun carryLegacyUrls(prior: WorkspaceConnection?, conn: WorkspaceConnection): List<String> {
    if (prior == null) return conn.legacyBaseUrls
    return (conn.legacyBaseUrls + prior.legacyBaseUrls + prior.baseUrl)
        .filter { it != conn.baseUrl }
        .distinct()
}

/** The identity a manual row's OWN host reported for it: `GET /health`'s
 *  `host_id` and the workspace id from that host's `GET /workspaces`. Either
 *  half null means "unverified" — which merges with nothing. */
data class VerifiedIdentity(
    val connectionId: String,
    val hostId: String?,
    val workspaceId: String?,
)

/** Rows that still lack a stable host/workspace identity tuple remain eligible for verified
 * adoption on every fleet refresh, including the URL-as-host handles persisted by #472. */
fun unresolvedLegacyConnections(
    connections: List<WorkspaceConnection>,
): List<WorkspaceConnection> = connections.filterNot {
    it.hasStableIdentityTuple()
}

/** Verify the identity of a persisted pre-host-model row through its host root.
 * A #472-derived legacy row already knows its workspace id even though its
 * `hostId` is still a URL; that id selects the right workspace when the host
 * serves several. A truly old row with no id remains adoptable only when the
 * verified host exposes exactly one workspace. */
suspend fun verifyLegacyIdentity(
    api: SpecApi,
    connection: WorkspaceConnection,
): VerifiedIdentity? {
    val workspaceHealth = try {
        api.health(connection)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    val workspaceHostId = workspaceHealth?.hostId?.takeIf { it.isNotBlank() }
    val workspaceHealthId = workspaceHealth?.workspaceId?.takeIf { it.isNotBlank() }
    if (workspaceHostId != null && workspaceHealthId != null) {
        return VerifiedIdentity(connection.id, workspaceHostId, workspaceHealthId)
    }
    val base = connection.baseUrl.trimEnd('/')
    val knownWorkspaceId = connection.workspaceId
    val workspaceSuffix = knownWorkspaceId?.let { "/workspaces/$it" }
    val hostRoot = when {
        workspaceSuffix != null && base.endsWith(workspaceSuffix) -> base.removeSuffix(workspaceSuffix)
        connection.hostId?.startsWith("http://") == true ||
            connection.hostId?.startsWith("https://") == true -> connection.hostId.trimEnd('/')
        else -> base
    }
    val hostId = api.hostHealth(hostRoot, recordContact = false).hostId ?: return null
    val workspaces = api.listWorkspaces(
        hostRoot,
        connection.token,
        recordContact = false,
    )
    val workspaceId = if (knownWorkspaceId != null) {
        knownWorkspaceId.takeIf { id -> workspaces.any { it.id == id } }
    } else {
        workspaces.singleOrNull()?.id
    } ?: return null
    return VerifiedIdentity(connection.id, hostId, workspaceId)
}

data class LegacyIdentityVerification(
    val identities: List<VerifiedIdentity>,
    val requiresRePair: Boolean,
)

/** Probe each still-unresolved row once per fleet refresh. One expired credential is reported but
 * does not prevent unrelated rows from being verified; cancellation still ends the whole refresh. */
suspend fun verifyLegacyIdentities(
    api: SpecApi,
    connections: List<WorkspaceConnection>,
): LegacyIdentityVerification {
    var requiresRePair = false
    val identities = buildList {
        for (connection in connections) {
            try {
                verifyLegacyIdentity(api, connection)?.let(::add)
            } catch (_: RePairNeededException) {
                requiresRePair = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // An unreachable legacy row remains unresolved for the next refresh.
            }
        }
    }
    return LegacyIdentityVerification(identities, requiresRePair)
}

/**
 * Fold host-discovered connections into the stored list, merging a manually
 * paired row into its discovered twin ONLY on a verified (host_id, workspace_id)
 * tuple (#471).
 *
 * Pure over already-fetched identity: the probing is the caller's, so the merge
 * rule itself is decidable in a test. Names are deliberately not consulted —
 * by #472's premise the same name can name different workspaces on different
 * hosts, so a name match yields two rows, which is the honest answer.
 *
 * A merged row keeps the operator's [WorkspaceConnection.id] (nav routes and
 * notification ids already reference it) and identity overrides, gains the host
 * tuple and state, and retains its pre-adoption URL in
 * [WorkspaceConnection.legacyBaseUrls].
 */
fun adoptManualConnections(
    existing: List<WorkspaceConnection>,
    discovered: List<WorkspaceConnection>,
    identities: List<VerifiedIdentity>,
): List<WorkspaceConnection> {
    val verified = identities.filter { it.hostId != null && it.workspaceId != null }
        .associateBy { it.connectionId }
    return discovered.fold(existing) { acc, found ->
        val manual = acc.firstOrNull { row ->
            !sameWorkspace(row, found) && verified[row.id]
                ?.let { it.hostId == found.hostId && it.workspaceId == found.workspaceId } == true
        }
        if (manual == null) upsertConnection(acc, found)
        else acc
            .filterNot { it.id != manual.id && sameWorkspace(it, found) }
            .map { if (it.id == manual.id) adopt(manual, found) else it }
    }
}


/** Reconcile one host from an authoritative `GET /workspaces` response.
 * Existing rows missing from [discovered] are gone on the host and must not
 * remain as permanent false failures; other hosts and manual rows are untouched. */
fun replaceHostConnections(
    existing: List<WorkspaceConnection>,
    hostId: String,
    discovered: List<WorkspaceConnection>,
    identities: List<VerifiedIdentity>,
): List<WorkspaceConnection> {
    val liveWorkspaceIds = discovered.mapNotNullTo(mutableSetOf()) { it.workspaceId }
    return adoptManualConnections(existing, discovered, identities).filterNot {
        it.hostId == hostId && it.workspaceId !in liveWorkspaceIds
    }
}

/** The merge itself. The manual row's standing token is dropped: it is rejected
 *  for relay-borne requests, and an Authorization header on the row would
 *  suppress the short-lived bearer the refresh interceptor mints. */
private fun adopt(manual: WorkspaceConnection, found: WorkspaceConnection): WorkspaceConnection =
    found.copy(
        id = manual.id,
        token = null,
        workspaceName = found.workspaceName.ifBlank { manual.workspaceName },
        colorOverride = manual.colorOverride ?: found.colorOverride,
        glyphOverride = manual.glyphOverride ?: found.glyphOverride,
        legacyBaseUrls = carryLegacyUrls(manual, found),
    )

/** Pure override editor: replace the identity override on the entry with [id] (null clears it,
 *  resetting that field to the auto-derived value). Used by the Projects tab edit affordance. */
fun applyIdentityOverride(
    list: List<WorkspaceConnection>,
    id: String,
    colorOverride: String?,
    glyphOverride: String?,
): List<WorkspaceConnection> =
    list.map { if (it.id == id) it.copy(colorOverride = colorOverride, glyphOverride = glyphOverride) else it }

/** Trim, strip a trailing slash, and require an http(s) scheme. Returns null if invalid. */
fun normalizedBaseUrl(input: String): String? {
    val t = input.trim().trimEnd('/')
    if (!t.startsWith("http://") && !t.startsWith("https://")) return null
    if (t.substringAfter("://").isBlank()) return null
    // Endpoints are appended as path segments; a query/fragment would swallow
    // them ("...?q=1/workspaces" puts the path inside the query string).
    if (t.contains('?') || t.contains('#')) return null
    return t
}
