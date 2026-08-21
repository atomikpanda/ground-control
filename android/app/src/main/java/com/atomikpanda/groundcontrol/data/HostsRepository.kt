package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val HOSTS = stringPreferencesKey("hosts")
private val RELAY_DOMAIN = stringPreferencesKey("relay_domain")
private val RELAY_FLEET_TOKEN = stringPreferencesKey("relay_fleet_token")
private val ROUTE_OWNERSHIP_GENERATION = longPreferencesKey("route_ownership_generation")

private data class HostRouteOwnership(
    val hostId: String,
    val publicUrl: String?,
    val refresh: String?,
    val directUrl: String?,
    val relayDomain: String?,
    val legacyPublicUrls: List<String>,
)

private data class ConnectionRouteOwnership(
    val id: String,
    val baseUrl: String?,
    val token: String?,
    val hostId: String?,
    val workspaceId: String?,
    val legacyBaseUrls: List<String>,
    val legacyConnectionIds: List<String>,
    val directToken: String?,
)

private data class RouteOwnership(
    val account: RelayAccount?,
    val hosts: List<HostRouteOwnership>,
    val connections: List<ConnectionRouteOwnership>,
)

private fun routeIdentity(value: String?): String? =
    value?.takeIf { it.isNotBlank() }?.let { normalizedBaseUrl(it) ?: it.trim().trimEnd('/') }

private fun routeOwnership(
    account: RelayAccount?,
    hosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
    validatedRoutes: Map<String, String> = emptyMap(),
): RouteOwnership = RouteOwnership(
    account = account,
    hosts = hosts.map { host ->
        HostRouteOwnership(
            hostId = host.hostId,
            publicUrl = validatedRoutes[host.hostId] ?: routeIdentity(host.publicUrl),
            refresh = host.refresh,
            directUrl = routeIdentity(host.directUrl),
            relayDomain = host.relayDomain,
            legacyPublicUrls = host.legacyPublicUrls.mapNotNull(::routeIdentity).distinct().sorted(),
        )
    }.sortedWith(
        compareBy(
            HostRouteOwnership::hostId,
            HostRouteOwnership::publicUrl,
            HostRouteOwnership::directUrl,
            HostRouteOwnership::relayDomain,
        ),
    ),
    connections = connections.map { connection ->
        ConnectionRouteOwnership(
            id = connection.id,
            baseUrl = routeIdentity(connection.baseUrl),
            token = connection.token,
            hostId = connection.hostId,
            workspaceId = connection.workspaceId,
            legacyBaseUrls = connection.legacyBaseUrls.mapNotNull(::routeIdentity).distinct().sorted(),
            legacyConnectionIds = connection.legacyConnectionIds.distinct().sorted(),
            directToken = connection.directToken,
        )
    }.sortedWith(compareBy(ConnectionRouteOwnership::id, ConnectionRouteOwnership::baseUrl)),
)

/** Advance the persisted optimistic generation exactly when network routing,
 * credential, or ownership state changes. Display and observation fields are
 * deliberately absent from [RouteOwnership]. */
internal fun routeOwnershipGenerationAfter(
    current: Long,
    previousAccount: RelayAccount?,
    previousHosts: List<HostConnection>,
    previousConnections: List<WorkspaceConnection>,
    updatedAccount: RelayAccount?,
    updatedHosts: List<HostConnection>,
    updatedConnections: List<WorkspaceConnection>,
    validatedRoutes: Map<String, String> = emptyMap(),
): Long {
    if (
        routeOwnership(previousAccount, previousHosts, previousConnections) ==
        routeOwnership(updatedAccount, updatedHosts, updatedConnections, validatedRoutes)
    ) {
        return current
    }
    check(current < Long.MAX_VALUE) { "Route ownership generation exhausted" }
    return current + 1
}


internal data class RelayAccountFleet(
    val hosts: List<HostConnection>,
    val connections: List<WorkspaceConnection>,
)

internal data class RouteOwnershipSnapshot(
    val account: RelayAccount?,
    val hosts: List<HostConnection>,
    val connections: List<WorkspaceConnection>,
    val generation: Long,
)


internal fun replaceRelayAccountFleet(
    previous: RelayAccount?,
    replacement: RelayAccount,
    hosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
): RelayAccountFleet {
    validateUniqueHostIds(hosts)
    if (previous == null || previous == replacement) {
        return RelayAccountFleet(hosts, connections)
    }
    val previousHosts = hosts.filter { it.relayDomain == previous.relayDomain }
    validateUniqueHostIds(previousHosts)
    val retainedDirectHosts = previousHosts.mapNotNull { host ->
        val directUrl = host.directUrl?.let(::normalizedBaseUrl) ?: return@mapNotNull null
        host.copy(
            subdomain = "",
            publicUrl = "",
            state = null,
            refresh = null,
            directUrl = directUrl,
            relayDomain = null,
            lastSeen = null,
            requestId = null,
            legacyPublicUrls = (host.legacyPublicUrls + host.publicUrl)
                .filter { it.isNotBlank() }
                .distinct(),
        )
    }
    val retainedDirectById = retainedDirectHosts.associateBy { it.hostId }
    val previousHostIds = previousHosts.mapTo(mutableSetOf()) { it.hostId }
    val ownershipByConnection = connections.associateWith { connection ->
        (legacyRouteOwnership(connection, hosts) as? LegacyRouteOwnership.Owned)
            ?.takeIf { it.hostId in previousHostIds }
    }
    val directCredentialByHostId = connections
        .mapNotNull { connection ->
            val evidence = ownershipByConnection[connection] ?: return@mapNotNull null
            val directHost = retainedDirectById[evidence.hostId] ?: return@mapNotNull null
            val credential = connection.directToken?.takeIf { it.isNotBlank() }
                ?: connection.token?.takeIf {
                    normalizedBaseUrl(evidence.hostBase) == directHost.directUrl?.let(::normalizedBaseUrl)
                }
            evidence.takeIf { connection.agreesWith(it, hosts) }?.hostId?.let { it to credential }
        }
        .filter { (_, credential) -> credential != null }
        .groupBy({ it.first }, { it.second!! })
        .mapValues { (_, credentials) -> credentials.distinct().singleOrNull() }
    return RelayAccountFleet(
        hosts = hosts.filterNot { it.relayDomain == previous.relayDomain } + retainedDirectHosts,
        connections = connections.mapNotNull { connection ->
            val evidence = ownershipByConnection[connection]
            if (evidence == null || !connection.agreesWith(evidence, hosts)) return@mapNotNull connection
            val directHost = retainedDirectById[evidence.hostId] ?: return@mapNotNull null
            val directCredential = directCredentialByHostId[directHost.hostId]
            if (directCredential == null && directCredentialByHostId.containsKey(directHost.hostId)) {
                return@mapNotNull connection
            }
            if (directCredential == null) return@mapNotNull null
            val directBase = evidence.workspaceId?.let {
                workspaceBaseUrl(directHost.directUrl!!, it)
            } ?: directHost.directUrl!!
            connection.copy(
                hostId = evidence.hostId,
                workspaceId = evidence.workspaceId,
                baseUrl = directBase,
                token = directCredential,
                state = null,
                legacyBaseUrls = (connection.legacyBaseUrls + connection.baseUrl)
                    .filter { it != directBase }
                    .distinct(),
                directToken = directCredential,
            )
        },
    )
}


internal fun replaceRelayDirectoryFleet(
    relayDomain: String,
    existingHosts: List<HostConnection>,
    replacementHosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
): RelayAccountFleet {
    validateUniqueHostIds(existingHosts)
    validateUniqueHostIds(replacementHosts)
    val replacementIds = replacementHosts.mapTo(mutableSetOf()) { it.hostId }
    require(
        existingHosts.none {
            it.hostId in replacementIds &&
                it.relayDomain != null &&
                it.relayDomain != relayDomain
        },
    ) { "Incoming host identity belongs to a different relay" }
    val removedHostIds = existingHosts
        .filter { it.relayDomain == relayDomain && it.hostId !in replacementIds }
        .mapTo(mutableSetOf()) { it.hostId }
    return RelayAccountFleet(
        hosts = replaceRelayHosts(existingHosts, relayDomain, replacementHosts),
        connections = connections.filterNot { connection ->
            val evidence = legacyRouteOwnership(connection, existingHosts) as? LegacyRouteOwnership.Owned
            evidence != null &&
                evidence.hostId in removedHostIds &&
                connection.agreesWith(evidence, existingHosts)
        },
    )
}

/** Verified adoption sources must still name one unambiguous persisted row.
 * The atomic callers reject rather than letting [adoptManualConnections]
 * collapse duplicate identity evidence. */
internal fun verifiedDiscoveryIdentitiesStillCurrent(
    identities: List<VerifiedIdentity>,
    currentConnections: List<WorkspaceConnection>,
): Boolean {
    if (identities.isEmpty()) return true
    val seenConnectionIds = mutableSetOf<String>()
    for (identity in identities) {
        val hostId = identity.hostId ?: continue
        val workspaceId = identity.workspaceId ?: continue
        if (
            identity.connectionId.isBlank() ||
            hostId.isBlank() ||
            workspaceId.isBlank() ||
            !seenConnectionIds.add(identity.connectionId) ||
            currentConnections.singleOrNull { it.id == identity.connectionId } == null
        ) {
            return false
        }
    }
    return true
}

/**
 * The fleet the phone knows about: one relay account plus the hosts it
 * enumerates. Mirrors [ConnectionsRepository] — same DataStore file, its own
 * keys — because these are the same operator-owned pairing state and a second
 * DataStore over one file would be a runtime error.
 *
 * The relay account lives here rather than in settings for the same reason:
 * without it the "hosts" key can never be refilled.
 */
class HostsRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)
    val hosts: Flow<List<HostConnection>> =
        dataStore.data.map { HostsCodec.decode(it[HOSTS] ?: "") }

    suspend fun snapshot(): List<HostConnection> =
        HostsCodec.decode(dataStore.data.first()[HOSTS] ?: "")

    val relayAccount: Flow<RelayAccount?> = dataStore.data.map { it.relayAccount() }


    internal suspend fun routeOwnershipSnapshot(): RouteOwnershipSnapshot =
        dataStore.data.first().routeOwnershipSnapshot()

    suspend fun setRelayAccount(account: RelayAccount) {
        dataStore.edit {
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            val currentConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: "")
            validateUniqueHostIds(currentHosts)
            val fleet = replaceRelayAccountFleet(
                previous = it.relayAccount(),
                replacement = account,
                hosts = currentHosts,
                connections = currentConnections,
            )
            it.advanceRouteOwnershipGeneration(
                updatedAccount = account,
                updatedHosts = fleet.hosts,
                updatedConnections = fleet.connections,
            )
            it[HOSTS] = HostsCodec.encode(fleet.hosts)
            it[CONNECTIONS] = ConnectionsCodec.encode(fleet.connections)
            it[RELAY_DOMAIN] = account.relayDomain
            it[RELAY_FLEET_TOKEN] = account.fleetToken
        }
    }

    /** DataStore serializes transforms, so host edits cannot lose concurrent
     * directory or connection writes. */
    private suspend fun mutate(transform: (List<HostConnection>) -> List<HostConnection>) {
        dataStore.edit {
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            val updatedHosts = transform(currentHosts)
            it.advanceRouteOwnershipGeneration(
                updatedAccount = it.relayAccount(),
                updatedHosts = updatedHosts,
                updatedConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: ""),
            )
            it[HOSTS] = HostsCodec.encode(updatedHosts)
        }
    }

    suspend fun upsert(host: HostConnection) = mutate { upsertHost(it, host) }

    suspend fun upsertAll(hosts: List<HostConnection>) =
        mutate { current -> hosts.fold(current) { acc, h -> upsertHost(acc, h) } }

    internal suspend fun replaceValidatedRelayDirectory(
        expectedAccount: RelayAccount,
        directory: ValidatedRelayDirectory,
        expectedGeneration: Long,
    ): Boolean {
        validateUniqueHostIds(directory.hosts)
        var applied = false
        dataStore.edit {
            if (
                it.relayAccount() != expectedAccount ||
                (it[ROUTE_OWNERSHIP_GENERATION] ?: 0L) != expectedGeneration
            ) {
                return@edit
            }
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            val currentConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: "")
            validateUniqueHostIds(currentHosts)
            validateUniqueHostIds(directory.hosts)
            val fleet = replaceRelayDirectoryFleet(
                relayDomain = expectedAccount.relayDomain,
                existingHosts = currentHosts,
                replacementHosts = directory.hosts,
                connections = currentConnections,
            )
            it.advanceRouteOwnershipGeneration(
                updatedAccount = expectedAccount,
                updatedHosts = fleet.hosts,
                updatedConnections = fleet.connections,
                validatedRoutes = directory.hosts.mapNotNull { host ->
                    host.publicUrl.takeIf(String::isNotBlank)?.let { host.hostId to it }
                }.toMap(),
            )
            it[HOSTS] = HostsCodec.encode(fleet.hosts)
            it[CONNECTIONS] = ConnectionsCodec.encode(fleet.connections)
            applied = true
        }
        return applied
    }

    suspend fun markRelayUnreachable(
        expectedAccount: RelayAccount,
        expectedGeneration: Long,
    ): Boolean {
        var applied = false
        dataStore.edit {
            if ((it[ROUTE_OWNERSHIP_GENERATION] ?: 0L) != expectedGeneration) return@edit
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            val updatedHosts = markRelayUnreachable(currentHosts, expectedAccount.relayDomain)
            it.advanceRouteOwnershipGeneration(
                updatedAccount = it.relayAccount(),
                updatedHosts = updatedHosts,
                updatedConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: ""),
            )
            it[HOSTS] = HostsCodec.encode(updatedHosts)
            applied = true
        }
        return applied
    }

    /** Apply one selected discovery only while the persisted route/ownership
     * generation that issued its network work remains current. */
    internal suspend fun applyDiscoveredWorkspace(
        expectedGeneration: Long,
        directHostId: String?,
        discovered: WorkspaceConnection,
        identities: List<VerifiedIdentity>,
        activatePriorDirectToken: Boolean,
        directUrl: String?,
        runnerState: String?,
        contactedAtMillis: Long,
    ): Boolean {
        var applied = false
        dataStore.edit {
            if ((it[ROUTE_OWNERSHIP_GENERATION] ?: 0L) != expectedGeneration) return@edit
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            val currentConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: "")
            if (!verifiedDiscoveryIdentitiesStillCurrent(identities, currentConnections)) return@edit
            val updatedHosts = if (directUrl != null) {
                val hostId = directHostId ?: return@edit
                if (currentHosts.singleOrNull { host -> host.hostId == hostId } == null) return@edit
                recordDirectHostDiscovery(
                    hosts = currentHosts,
                    hostId = hostId,
                    directUrl = directUrl,
                    runnerState = runnerState,
                    contactedAtMillis = contactedAtMillis,
                )
            } else {
                currentHosts
            }
            val updatedConnections = adoptManualConnections(
                existing = currentConnections,
                discovered = listOf(discovered),
                identities = identities,
                activatePriorDirectToken = activatePriorDirectToken,
            )
            it.advanceRouteOwnershipGeneration(
                updatedAccount = it.relayAccount(),
                updatedHosts = updatedHosts,
                updatedConnections = updatedConnections,
            )
            it[HOSTS] = HostsCodec.encode(updatedHosts)
            it[CONNECTIONS] = ConnectionsCodec.encode(updatedConnections)
            applied = true
        }
        return applied
    }


    /** Atomically apply one host response only while the persisted generation
     * captured with its fleet target remains current. */
    internal suspend fun applyHostWorkspaceRefresh(
        hostId: String,
        hostBase: String,
        expectedDirectOnly: Boolean,
        expectedSourceGeneration: WorkspaceRefreshGeneration,
        contactedAtMillis: Long,
        discovered: List<WorkspaceConnection>,
        identities: List<VerifiedIdentity>,
    ): Boolean {
        var applied = false
        dataStore.edit {
            if (
                (it[ROUTE_OWNERSHIP_GENERATION] ?: 0L) !=
                expectedSourceGeneration.routeOwnershipGeneration
            ) {
                return@edit
            }
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            if (currentHosts.singleOrNull { it.hostId == hostId } == null) return@edit
            val currentConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: "")
            if (!verifiedDiscoveryIdentitiesStillCurrent(identities, currentConnections)) return@edit
            val updatedHosts = recordHostContact(
                currentHosts,
                hostId,
                hostBase,
                contactedAtMillis,
            )
            val updatedConnections = replaceHostConnections(
                existing = currentConnections,
                hostId = hostId,
                discovered = discovered,
                identities = identities,
                hosts = currentHosts,
                activatePriorDirectToken = expectedDirectOnly,
            )
            it.advanceRouteOwnershipGeneration(
                updatedAccount = it.relayAccount(),
                updatedHosts = updatedHosts,
                updatedConnections = updatedConnections,
            )
            it[HOSTS] = HostsCodec.encode(updatedHosts)
            it[CONNECTIONS] = ConnectionsCodec.encode(updatedConnections)
            applied = true
        }
        return applied
    }

    /** Every successful request through the shared host-aware client advances
     * the phone's own freshness evidence, regardless of which UI initiated it. */
    suspend fun recordContact(
        hostId: String,
        hostBase: String,
        contactedAtMillis: Long = System.currentTimeMillis(),
    ) = mutate { current -> recordHostContact(current, hostId, hostBase, contactedAtMillis) }

    suspend fun remove(hostId: String) = mutate { list -> list.filterNot { it.hostId == hostId } }
}

private fun androidx.datastore.preferences.core.Preferences.relayAccount(): RelayAccount? {
    val domain = this[RELAY_DOMAIN]?.takeIf { it.isNotBlank() } ?: return null
    val token = this[RELAY_FLEET_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    return RelayAccount(domain, token)
}

private fun androidx.datastore.preferences.core.Preferences.routeOwnershipSnapshot():
    RouteOwnershipSnapshot = RouteOwnershipSnapshot(
        account = relayAccount(),
        hosts = HostsCodec.decode(this[HOSTS] ?: ""),
        connections = ConnectionsCodec.decode(this[CONNECTIONS] ?: ""),
        generation = this[ROUTE_OWNERSHIP_GENERATION] ?: 0L,
    )

internal fun androidx.datastore.preferences.core.MutablePreferences.advanceRouteOwnershipGeneration(
    updatedAccount: RelayAccount?,
    updatedHosts: List<HostConnection>,
    updatedConnections: List<WorkspaceConnection>,
    validatedRoutes: Map<String, String> = emptyMap(),
) {
    val current = this[ROUTE_OWNERSHIP_GENERATION] ?: 0L
    val updated = routeOwnershipGenerationAfter(
        current = current,
        previousAccount = relayAccount(),
        previousHosts = HostsCodec.decode(this[HOSTS] ?: ""),
        previousConnections = ConnectionsCodec.decode(this[CONNECTIONS] ?: ""),
        updatedAccount = updatedAccount,
        updatedHosts = updatedHosts,
        updatedConnections = updatedConnections,
        validatedRoutes = validatedRoutes,
    )
    if (updated != current) this[ROUTE_OWNERSHIP_GENERATION] = updated
}

internal fun androidx.datastore.preferences.core.MutablePreferences.advanceRouteOwnershipGeneration(
    updatedConnections: List<WorkspaceConnection>,
) = advanceRouteOwnershipGeneration(
    updatedAccount = relayAccount(),
    updatedHosts = HostsCodec.decode(this[HOSTS] ?: ""),
    updatedConnections = updatedConnections,
)

/**
 * The app's HTTP client: host-aware everywhere (#471). Every entry point that
 * talks to a workspace — the UI, the watch service, its backstop worker and the
 * notification reply worker — builds it here, so a host-derived connection gets
 * its short-lived bearer minted on ANY of those paths, not just the foreground.
 */
fun appHttpClient(context: Context): HostClient {
    val repo = HostsRepository(context.applicationContext)
    return hostAwareClient(
        engine = OkHttp.create(),
        hosts = { repo.snapshot() },
        onHostContact = { hostId, base -> repo.recordContact(hostId, base) },
    )
}
