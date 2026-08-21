package com.atomikpanda.groundcontrol.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.net.URI
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
    /** Local handles retired when duplicate rows converge. Persisted navigation
     *  and notification work can still use these after adoption. */
    val legacyConnectionIds: List<String> = emptyList(),
    /** Standing token retained only to restore an operator-paired direct route
     * if the relay account that temporarily adopted this row is replaced. */
    val directToken: String? = null,
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
    directToken = hostToken,
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
        !hostId.startsWith("http://", ignoreCase = true) &&
        !hostId.startsWith("https://", ignoreCase = true)

/** Resolve a current local handle before consulting unambiguous retired aliases. */
internal fun Iterable<WorkspaceConnection>.findByConnectionId(
    connectionId: String,
): WorkspaceConnection? {
    var aliasMatch: WorkspaceConnection? = null
    var ambiguousAlias = false
    for (connection in this) {
        if (connection.id == connectionId) return connection
        if (connectionId in connection.legacyConnectionIds) {
            if (aliasMatch == null) aliasMatch = connection else ambiguousAlias = true
        }
    }
    return aliasMatch.takeUnless { ambiguousAlias }
}

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
 *
 * [preservePriorDirectToken] is true for discovery, where an omitted credential
 * is not a request to erase the operator's direct route. [activatePriorDirectToken]
 * additionally makes that retained credential active on a direct-only route.
 * Explicit pairing disables both so a blank token clears both credential fields.
 */
fun upsertConnection(
    existing: List<WorkspaceConnection>,
    conn: WorkspaceConnection,
    preservePriorDirectToken: Boolean = true,
    activatePriorDirectToken: Boolean = false,
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
    val retainedDirectToken = conn.directToken ?: conn.token ?: if (preservePriorDirectToken) {
        prior?.directToken ?: prior?.token
    } else {
        null
    }
    val mergedId = if (prior != null && sameWorkspace(prior, conn)) prior.id else conn.id
    val merged = conn.copy(
        // On an IDENTITY match the row id is the phone's local handle (nav
        // routes, notification ids) and survives — otherwise the next directory
        // read would re-mint it and undo an adoption. A legacy URL/id match is a
        // manual re-pair, where the incoming id is the operator's new one.
        id = mergedId,
        token = conn.token ?: retainedDirectToken.takeIf { activatePriorDirectToken },
        colorOverride = conn.colorOverride ?: prior?.colorOverride,
        glyphOverride = conn.glyphOverride ?: prior?.glyphOverride,
        directToken = retainedDirectToken,
        legacyBaseUrls = carryLegacyUrls(listOfNotNull(prior), conn),
        legacyConnectionIds = carryLegacyConnectionIds(listOfNotNull(prior), conn, mergedId),
    )
    return existing.filterNot(matches) + merged
}

/** Every base URL these rows have answered on before [conn]'s current one. */
private fun carryLegacyUrls(
    priors: List<WorkspaceConnection>,
    conn: WorkspaceConnection,
): List<String> =
    (
        conn.legacyBaseUrls +
            priors.flatMap { it.legacyBaseUrls + it.baseUrl }
    )
        .filter { it != conn.baseUrl }
        .distinct()

/** Every local handle retired while folding [priors] into [currentId]. */
private fun carryLegacyConnectionIds(
    priors: List<WorkspaceConnection>,
    conn: WorkspaceConnection,
    currentId: String,
): List<String> =
    (
        conn.legacyConnectionIds +
            priors.flatMap { it.legacyConnectionIds + it.id }
    )
        .filter { it != currentId }
        .distinct()

/** A workspace tuple confirmed through a known host's authenticated current
 * route. Either half null means "unverified" — which merges with nothing. */
data class VerifiedIdentity(
    val connectionId: String,
    val hostId: String?,
    val workspaceId: String?,
)

/** Persisted routing generation plus the exact workspace sources used to
 * authorize one host refresh. The generation supplies ABA protection; sources
 * only derive the standing direct credential sent by the request. */
internal data class WorkspaceRefreshGeneration(
    val routeOwnershipGeneration: Long,
    val sources: List<WorkspaceConnection>,
) {
    /** A direct refresh is authorized only when every source agrees on one
     * nonblank standing credential. Relay refreshes authenticate from the host. */
    val uniqueCredential: String?
        get() = sources.mapNotNull { connection ->
            connection.directToken?.takeIf { it.isNotBlank() }
                ?: connection.token?.takeIf { it.isNotBlank() }
        }.distinct().singleOrNull()
}


internal fun workspaceRefreshGeneration(
    routeOwnershipGeneration: Long,
    connections: List<WorkspaceConnection>,
    hostId: String,
    hosts: List<HostConnection>,
    identities: List<VerifiedIdentity>,
): WorkspaceRefreshGeneration? {
    val verifiedSourceIds = identities
        .filter { it.hostId == hostId && !it.workspaceId.isNullOrBlank() }
        .mapTo(mutableSetOf()) { it.connectionId }
    val sources = connections.filter { connection ->
        val evidence = legacyRouteOwnership(connection, hosts) as? LegacyRouteOwnership.Owned
        val agrees = evidence != null && connection.agreesWith(evidence, hosts)
        val verifiedLegacyOwner =
            connection.id in verifiedSourceIds && evidence?.hostId == hostId && agrees
        (evidence?.hostId == hostId && agrees) || verifiedLegacyOwner
    }.sortedBy { it.id }
    if (sources.map { it.id }.distinct().size != sources.size) return null
    return WorkspaceRefreshGeneration(routeOwnershipGeneration, sources)
}

/** Rows that still lack a stable host/workspace identity tuple remain eligible for verified
 * adoption on every fleet refresh, including the URL-as-host handles persisted by #472. */
fun unresolvedLegacyConnections(
    connections: List<WorkspaceConnection>,
): List<WorkspaceConnection> = connections.filterNot {
    it.hasStableIdentityTuple()
}
internal fun legacyConnectionsForDiscovery(
    connections: List<WorkspaceConnection>,
    hostBases: List<String>,
    workspaceId: String,
): List<WorkspaceConnection> {
    val matchingBases = hostBases.mapNotNull(::normalizedBaseUrl)
        .flatMap { listOf(it, workspaceBaseUrl(it, workspaceId)) }
        .toSet()
    return unresolvedLegacyConnections(connections).filter {
        normalizedBaseUrl(it.baseUrl)?.let(matchingBases::contains) == true
    }
}


/** Canonical result of matching a persisted legacy route against the complete
 * host snapshot. URL evidence alone establishes ownership; stored IDs are a
 * separate agreement check before any side effect. */
internal sealed interface LegacyRouteOwnership {
    data object Unknown : LegacyRouteOwnership
    data object Ambiguous : LegacyRouteOwnership
    data class Owned(
        val hostId: String,
        val hostBase: String,
        val workspaceId: String?,
    ) : LegacyRouteOwnership
}

internal fun legacyRouteOwnership(
    connection: WorkspaceConnection,
    candidateHosts: List<HostConnection>,
): LegacyRouteOwnership {
    if (
        candidateHosts
            .map(HostConnection::hostId)
            .filter(String::isNotBlank)
            .distinct()
            .size != candidateHosts.count { it.hostId.isNotBlank() }
    ) {
        return LegacyRouteOwnership.Ambiguous
    }
    val connectionBase = normalizedBaseUrl(connection.baseUrl)
        ?: return LegacyRouteOwnership.Unknown
    val claims = candidateHosts.flatMap { host ->
        if (host.hostId.isBlank()) return@flatMap emptyList()
        (listOfNotNull(host.directUrl, host.publicUrl) + host.legacyPublicUrls)
            .mapNotNull(::normalizedBaseUrl)
            .distinct()
            .mapNotNull { base -> ownershipClaim(connectionBase, host.hostId, base) }
    }.distinct()
    return when (claims.size) {
        0 -> LegacyRouteOwnership.Unknown
        1 -> claims.single()
        else -> LegacyRouteOwnership.Ambiguous
    }
}

/** A stored identity is corroboration, never a substitute for route evidence.
 * URL-valued pre-ownership handles, including workspace routes, are accepted
 * only when they cannot resolve to a different host in this complete candidate snapshot. */
internal fun WorkspaceConnection.agreesWith(
    evidence: LegacyRouteOwnership.Owned,
    candidateHosts: List<HostConnection>,
): Boolean {
    val storedHostAgrees = when {
        hostId.isNullOrBlank() -> true
        hostId.startsWith("http://", ignoreCase = true) ||
            hostId.startsWith("https://", ignoreCase = true) -> {
            val storedRouteOwnership = normalizedBaseUrl(hostId)?.let { storedBase ->
                legacyRouteOwnership(copy(baseUrl = storedBase), candidateHosts)
            }
            when (storedRouteOwnership) {
                is LegacyRouteOwnership.Owned ->
                    storedRouteOwnership.hostId == evidence.hostId &&
                        (
                            storedRouteOwnership.workspaceId == null ||
                                storedRouteOwnership.workspaceId == evidence.workspaceId
                        )
                LegacyRouteOwnership.Ambiguous -> false
                LegacyRouteOwnership.Unknown, null -> true
            }
        }
        else -> hostId == evidence.hostId
    }
    return storedHostAgrees &&
        (workspaceId.isNullOrBlank() || workspaceId == evidence.workspaceId)
}

private fun ownershipClaim(
    connectionBase: String,
    hostId: String,
    candidateBase: String,
): LegacyRouteOwnership.Owned? {
    val connection = runCatching { URI(connectionBase) }.getOrNull() ?: return null
    val candidate = runCatching { URI(candidateBase) }.getOrNull() ?: return null
    if (connection.scheme != candidate.scheme || connection.rawAuthority != candidate.rawAuthority) return null
    val connectionSegments = safeRawPathSegments(connection.rawPath) ?: return null
    val candidateSegments = safeRawPathSegments(candidate.rawPath) ?: return null
    if (connectionSegments == candidateSegments) {
        return LegacyRouteOwnership.Owned(hostId, candidateBase, null)
    }
    val suffix = connectionSegments.drop(candidateSegments.size)
    if (
        connectionSegments.size > candidateSegments.size &&
        connectionSegments.take(candidateSegments.size) == candidateSegments &&
        suffix.size == 2 &&
        suffix[0] == "workspaces" &&
        suffix[1].isNotBlank()
    ) {
        return LegacyRouteOwnership.Owned(hostId, candidateBase, suffix[1])
    }
    return null
}

private val ENCODED_PATH_SEPARATOR = Regex("%2f|%5c", RegexOption.IGNORE_CASE)
private val ENCODED_DOT = Regex("%2e", RegexOption.IGNORE_CASE)

private fun safeRawPathSegments(path: String): List<String>? {
    val segments = path.split('/').filter(String::isNotEmpty)
    return segments.takeUnless { segments.any { segment ->
        val dotDecoded = segment.replace(ENCODED_DOT, ".")
        dotDecoded == "." ||
            dotDecoded == ".." ||
            ENCODED_PATH_SEPARATOR.containsMatchIn(segment)
    } }
}


/** Verify a legacy row through the uniquely evidenced host's current
 * authenticated route. Stale direct or relay URLs are identity aliases only. */
suspend fun verifyLegacyIdentity(
    api: SpecApi,
    connection: WorkspaceConnection,
    hosts: List<HostConnection>,
): VerifiedIdentity? {
    val evidence = legacyRouteOwnership(connection, hosts) as? LegacyRouteOwnership.Owned
        ?: return null
    if (!connection.agreesWith(evidence, hosts)) return null
    val host = hosts.singleOrNull { it.hostId == evidence.hostId } ?: return null
    val (_, workspaces) = reachableHostWorkspaces(
        api = api,
        host = host,
        token = (connection.directToken ?: connection.token)
            .takeIf { host.acceptsDirectCredential() },
        recordContact = false,
    ) ?: return null
    val workspaceId = when (val claimed = evidence.workspaceId) {
        null -> workspaces.singleOrNull()?.id
        else -> claimed.takeIf { id -> workspaces.any { it.id == id } }
    } ?: return null
    return VerifiedIdentity(connection.id, host.hostId, workspaceId)
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
    hosts: List<HostConnection>,
): LegacyIdentityVerification {
    var requiresRePair = false
    val identities = buildList {
        for (connection in connections) {
            try {
                verifyLegacyIdentity(api, connection, hosts)?.let(::add)
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
 * A merged group keeps an existing stable twin's id; without one, the
 * lexicographically first verified legacy id is canonical. Every duplicate
 * source is removed in the same fold, while identity overrides, unique direct
 * credentials, and prior URLs are carried into the canonical row.
 */
fun adoptManualConnections(
    existing: List<WorkspaceConnection>,
    discovered: List<WorkspaceConnection>,
    identities: List<VerifiedIdentity>,
    activatePriorDirectToken: Boolean = false,
): List<WorkspaceConnection> {
    val verified = identities.filter { it.hostId != null && it.workspaceId != null }
        .associateBy { it.connectionId }
    return discovered.fold(existing) { acc, found ->
        val stableTwins = acc.filter { sameWorkspace(it, found) }
        val verifiedLegacySources = acc.filter { row ->
            !sameWorkspace(row, found) && verified[row.id]
                ?.let { it.hostId == found.hostId && it.workspaceId == found.workspaceId } == true
        }
        val sources = (stableTwins + verifiedLegacySources)
            .distinctBy { it.id }
        val canonical = stableTwins.minByOrNull { it.id }
            ?: verifiedLegacySources.minByOrNull { it.id }
        if (canonical == null) {
            upsertConnection(
                acc,
                found,
                activatePriorDirectToken = activatePriorDirectToken,
            )
        } else {
            val priors = listOf(canonical) +
                sources.filterNot { it.id == canonical.id }.sortedBy { it.id }
            val sourceIds = sources.mapTo(mutableSetOf()) { it.id }
            acc.filterNot { it.id in sourceIds } +
                adopt(priors, found, activatePriorDirectToken)
        }
    }
}


/** Reconcile one host from an authoritative `GET /workspaces` response. A row
 * is removed only when the complete pre-refresh host snapshot uniquely proves
 * ownership and agrees with every stored identity field. */
fun replaceHostConnections(
    existing: List<WorkspaceConnection>,
    hostId: String,
    discovered: List<WorkspaceConnection>,
    identities: List<VerifiedIdentity>,
    hosts: List<HostConnection>,
    activatePriorDirectToken: Boolean = false,
): List<WorkspaceConnection> {
    val liveWorkspaceIds = discovered.mapNotNullTo(mutableSetOf()) { it.workspaceId }
    return adoptManualConnections(
        existing,
        discovered,
        identities,
        activatePriorDirectToken,
    ).filterNot { connection ->
        val evidence = legacyRouteOwnership(connection, hosts) as? LegacyRouteOwnership.Owned
        evidence != null &&
            evidence.hostId == hostId &&
            connection.agreesWith(evidence, hosts) &&
            evidence.workspaceId != null &&
            evidence.workspaceId !in liveWorkspaceIds
    }
}

/** The merge itself. Relay discovery supplies no standing token, so relay-borne
 * rows cannot suppress the refresh interceptor's bearer. Verified direct
 * discovery supplies its direct token and retains it. */
private fun adopt(
    priors: List<WorkspaceConnection>,
    found: WorkspaceConnection,
    activatePriorDirectToken: Boolean,
): WorkspaceConnection {
    val canonical = priors.first()
    val priorDirectToken = priors.mapNotNull { prior ->
        prior.directToken?.takeIf { it.isNotBlank() }
            ?: prior.token?.takeIf { it.isNotBlank() }
    }.distinct().singleOrNull()
    val retainedDirectToken =
        found.directToken?.takeIf { it.isNotBlank() }
            ?: found.token?.takeIf { it.isNotBlank() }
            ?: priorDirectToken
    return found.copy(
        id = canonical.id,
        token = found.token ?: retainedDirectToken.takeIf { activatePriorDirectToken },
        directToken = retainedDirectToken,
        workspaceName = found.workspaceName.ifBlank { canonical.workspaceName },
        colorOverride = priors.firstNotNullOfOrNull { it.colorOverride } ?: found.colorOverride,
        glyphOverride = priors.firstNotNullOfOrNull { it.glyphOverride } ?: found.glyphOverride,
        legacyBaseUrls = carryLegacyUrls(priors, found),
        legacyConnectionIds = carryLegacyConnectionIds(priors, found, canonical.id),
    )
}

/** Pure override editor: replace the identity override on the entry with [id] (null clears it,
 *  resetting that field to the auto-derived value). Used by the Projects tab edit affordance. */
fun applyIdentityOverride(
    list: List<WorkspaceConnection>,
    id: String,
    colorOverride: String?,
    glyphOverride: String?,
): List<WorkspaceConnection> =
    list.map { if (it.id == id) it.copy(colorOverride = colorOverride, glyphOverride = glyphOverride) else it }

/** Trim, strip a trailing slash, and require an http(s) URL. Scheme and host are
 * case-insensitive; user-info and path data retain their original case. */
fun normalizedBaseUrl(input: String): String? {
    val raw = input.trim().trimEnd('/')
    if (raw.contains('?') || raw.contains('#')) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
        ?.takeIf { it == "http" || it == "https" }
        ?: return null
    val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
    if (uri.host.isNullOrBlank()) return null

    val hostStart = uri.rawUserInfo?.let { it.length + 1 } ?: 0
    val hostEnd = if (authority.getOrNull(hostStart) == '[') {
        authority.indexOf(']', hostStart).takeIf { it >= 0 }?.plus(1)
    } else {
        authority.indexOf(':', hostStart).takeIf { it >= 0 } ?: authority.length
    } ?: return null
    val rawHost = authority.substring(hostStart, hostEnd)
    if (rawHost.isBlank()) return null
    val normalizedAuthority = authority.replaceRange(hostStart, hostEnd, rawHost.lowercase())
    return "$scheme://$normalizedAuthority${uri.rawPath.orEmpty()}"
}
