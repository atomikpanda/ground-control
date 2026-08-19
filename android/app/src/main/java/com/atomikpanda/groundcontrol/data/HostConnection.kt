package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.HostInfo
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One host in the fleet, as the phone remembers it (#471).
 *
 * A host — not a workspace — is what the relay directory enumerates and what a
 * credential belongs to: [refresh] is the standing credential the phone
 * exchanges at the host's authenticated public `POST /host/token` for a
 * short-lived bearer. Persisting it keeps the cached public route usable when
 * directory discovery is unavailable (AC9).
 *
 * Every field has a default: an install that predates #471 has no "hosts" key
 * at all, and a field added later must decode against JSON written before it
 * existed — missing keys are covered by defaults, NOT by `ignoreUnknownKeys`.
 */
@Serializable
data class HostConnection(
    val hostId: String,
    /** The host's own name for itself, from the directory. */
    val label: String = "",
    val subdomain: String = "",
    /** The relay-borne URL the directory published. */
    val publicUrl: String = "",
    /** Last-known directory state: online / offline / pending-approval / … */
    val state: String? = null,
    /** Persisted refresh credential used to mint short-lived host bearers
     * across phone process restarts. */
    val refresh: String? = null,
    /** A LAN/tailnet URL the operator entered and we found reachable. Used
     * directly only until a relay refresh credential exists. */
    val directUrl: String? = null,
    /** Operator's name for this host; survives every re-read of the directory. */
    val labelOverride: String? = null,
    /** Which relay account this host was discovered through. */
    val relayDomain: String? = null,
    /** Relay-clock epoch seconds of the host's last registration. */
    val lastSeen: Double? = null,
    /** Runner block from the directory (`disabled` until #473 fills it). */
    val runnerState: String? = null,
    /** Enroll-store request id for a `pending-approval` row (no host_id yet). */
    val requestId: String? = null,
    /** Wall-clock millis of the phone's last SUCCESSFUL read of this host — the
     *  ladder's staleness input, and the phone's own evidence rather than the
     *  directory's claim. */
    val lastContactAtMillis: Long? = null,
)

/** Candidate bases in reachability order. A LAN/tailnet address is eligible
 * only before this host owns a persisted refresh credential; once it does,
 * sending that credential to an address learned from unauthenticated health
 * would disclose it. */
fun HostConnection.hostBases(): List<String> =
    listOfNotNull(directUrl.takeIf { refresh == null }, publicUrl)
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()

/** The preferred candidate for address construction before reachability is
 * known. Fleet refresh replaces it with the first base it actually reaches. */
fun HostConnection.hostBase(): String = hostBases().firstOrNull().orEmpty()

/** Probe candidates in order and retain the base that answered. Authentication
 * failures requiring operator action and cancellation are not reachability
 * failures, so they propagate instead of silently falling through. */
suspend fun reachableHostWorkspaces(
    api: SpecApi,
    host: HostConnection,
): Pair<String, List<WorkspaceInfo>>? {
    for (base in host.hostBases()) {
        try {
            return base to api.listWorkspaces(
                base,
                null,
                allowHostFallback = false,
                recordContact = false,
                hostId = host.hostId,
            )
        } catch (_: IOException) {
            // Only a transport failure justifies trying another candidate.
        }
    }
    return null
}

/** The network-derived inputs for one host's atomic connection update. This is
 * the non-DataStore core of Settings `refreshFleet`, kept JVM-testable so legacy
 * adoption is covered through the same host-root, multi-workspace path used in
 * production. */
data class HostWorkspaceRefresh(
    val hostBase: String,
    val connections: List<WorkspaceConnection>,
    val identities: List<VerifiedIdentity>,
)

suspend fun refreshHostWorkspaceConnections(
    api: SpecApi,
    host: HostConnection,
    identities: List<VerifiedIdentity> = emptyList(),
): HostWorkspaceRefresh? {
    val (base, workspaces) = reachableHostWorkspaces(api, host) ?: return null
    return HostWorkspaceRefresh(
        hostBase = base,
        connections = workspaces.map { info ->
            deriveConnection(
                hostBase = base,
                hostToken = null,
                hostId = host.hostId,
                workspaceId = info.id,
                workspaceName = info.name,
                state = info.state,
            )
        },
        identities = identities,
    )
}

/** Pure freshness update used by both DataStore and the host-aware-client test. */
fun recordHostContact(
    hosts: List<HostConnection>,
    hostId: String,
    hostBase: String,
    contactedAtMillis: Long,
): List<HostConnection> = hosts.map { host ->
    if (host.hostId == hostId && hostBase in host.hostBases()) {
        host.copy(
            lastContactAtMillis = contactedAtMillis.coerceAtLeast(
                host.lastContactAtMillis ?: contactedAtMillis,
            ),
        )
    } else host
}

/** Persist the observations made by one reachable direct-host discovery. */
fun recordDirectHostDiscovery(
    hosts: List<HostConnection>,
    hostId: String,
    directUrl: String,
    runnerState: String?,
    contactedAtMillis: Long,
): List<HostConnection> {
    val prior = hosts.firstOrNull { it.hostId == hostId }
    val updated = prior?.copy(
        directUrl = directUrl.trimEnd('/'),
        runnerState = runnerState,
        lastContactAtMillis = contactedAtMillis.coerceAtLeast(
            prior.lastContactAtMillis ?: contactedAtMillis,
        ),
    ) ?: HostConnection(
        hostId = hostId,
        directUrl = directUrl.trimEnd('/'),
        runnerState = runnerState,
        lastContactAtMillis = contactedAtMillis,
    )
    return upsertHost(hosts, updated)
}

/** What the operator sees: their own name for the host, else the host's. */
fun HostConnection.displayLabel(): String =
    labelOverride?.takeIf { it.isNotBlank() } ?: label.ifBlank { hostId }

/** Pure (de)serialization of the host list stored in DataStore. */
object HostsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HostConnection.serializer())

    fun encode(list: List<HostConnection>): String = json.encodeToString(serializer, list)

    fun decode(raw: String): List<HostConnection> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

/**
 * Pure upsert keyed on [HostConnection.hostId] — the only stable handle a host
 * has. Its subdomain, public URL and state all change on re-registration, so
 * keying on any of them would fork one host into two rows.
 *
 * Operator-owned fields ([HostConnection.labelOverride], [HostConnection.directUrl])
 * and the [HostConnection.refresh] credential carry forward when [host] omits
 * them: a directory read carries neither of the first two, and `refresh` is
 * published on `GET /hosts` and nowhere else.
 */
fun upsertHost(existing: List<HostConnection>, host: HostConnection): List<HostConnection> {
    val prior = existing.firstOrNull { it.hostId == host.hostId }
    val merged = host.copy(
        labelOverride = host.labelOverride ?: prior?.labelOverride,
        directUrl = host.directUrl ?: prior?.directUrl,
        refresh = host.refresh ?: prior?.refresh,
        lastContactAtMillis = host.lastContactAtMillis ?: prior?.lastContactAtMillis,
    )
    return if (prior == null) existing + merged
    else existing.map { if (it.hostId == host.hostId) merged else it }
}

/** A failed directory read makes that relay's state unknown without discarding
 * cached addresses, refresh credentials, or hosts belonging to another relay. */
fun markRelayUnreachable(
    existing: List<HostConnection>,
    relayDomain: String,
): List<HostConnection> = existing.map { host ->
    if (host.relayDomain == relayDomain) host.copy(state = null) else host
}

/** A successful directory response is authoritative for one relay. Entries no
 * longer present (notably an approved pending request) disappear, while
 * [upsertHost] carries local overrides and direct URLs onto the fresh rows. */
fun replaceRelayHosts(
    existing: List<HostConnection>,
    relayDomain: String,
    hosts: List<HostConnection>,
): List<HostConnection> {
    val retained = existing.filterNot { it.relayDomain == relayDomain }
    return hosts.fold(retained) { current, host ->
        val prior = existing.firstOrNull { it.hostId == host.hostId }
        val merged = if (prior == null) host else upsertHost(listOf(prior), host).single()
        upsertHost(current, merged)
    }
}

/** Project a directory entry into the stored model. Pending-approval rows have
 *  no `host_id` yet and are keyed by their enroll request id instead. */
fun hostFrom(info: HostInfo, relayDomain: String): HostConnection? {
    val id = info.hostId ?: info.requestId?.let { "pending:$it" } ?: return null
    return HostConnection(
        hostId = id,
        label = info.label,
        subdomain = info.subdomain,
        publicUrl = info.publicUrl,
        state = info.state,
        refresh = info.refresh,
        relayDomain = relayDomain,
        lastSeen = info.lastSeen,
        runnerState = info.runner?.state,
        requestId = info.requestId,
    )
}
