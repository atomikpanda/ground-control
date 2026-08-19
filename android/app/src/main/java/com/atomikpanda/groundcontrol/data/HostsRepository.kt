package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val HOSTS = stringPreferencesKey("hosts")
private val RELAY_DOMAIN = stringPreferencesKey("relay_domain")
private val RELAY_FLEET_TOKEN = stringPreferencesKey("relay_fleet_token")


internal data class RelayAccountFleet(
    val hosts: List<HostConnection>,
    val connections: List<WorkspaceConnection>,
)

/** Complete account equality is the optimistic generation for all network-derived fleet writes. */
internal fun relayAccountMatchesExpected(
    current: RelayAccount?,
    expected: RelayAccount,
): Boolean = current == expected


internal fun replaceRelayAccountFleet(
    previous: RelayAccount?,
    replacement: RelayAccount,
    hosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
): RelayAccountFleet {
    if (previous == null || previous == replacement) {
        return RelayAccountFleet(hosts, connections)
    }
    val removedHostIds = hosts
        .filter { it.relayDomain == previous.relayDomain }
        .mapTo(mutableSetOf()) { it.hostId }
    return RelayAccountFleet(
        hosts = hosts.filterNot { it.relayDomain == previous.relayDomain },
        connections = connections.filterNot { it.hostId in removedHostIds },
    )
}


internal fun replaceRelayDirectoryFleet(
    relayDomain: String,
    existingHosts: List<HostConnection>,
    replacementHosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
): RelayAccountFleet {
    val replacementIds = replacementHosts.mapTo(mutableSetOf()) { it.hostId }
    val removedHostIds = existingHosts
        .filter { it.relayDomain == relayDomain && it.hostId !in replacementIds }
        .mapTo(mutableSetOf()) { it.hostId }
    return RelayAccountFleet(
        hosts = replaceRelayHosts(existingHosts, relayDomain, replacementHosts),
        connections = connections.filterNot { it.hostId in removedHostIds },
    )
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
class HostsRepository(private val context: Context) {
    val hosts: Flow<List<HostConnection>> =
        context.dataStore.data.map { HostsCodec.decode(it[HOSTS] ?: "") }

    suspend fun snapshot(): List<HostConnection> =
        HostsCodec.decode(context.dataStore.data.first()[HOSTS] ?: "")

    val relayAccount: Flow<RelayAccount?> = context.dataStore.data.map { it.relayAccount() }

    suspend fun relayAccountSnapshot(): RelayAccount? = context.dataStore.data.first().relayAccount()

    suspend fun setRelayAccount(account: RelayAccount) {
        context.dataStore.edit {
            val fleet = replaceRelayAccountFleet(
                previous = it.relayAccount(),
                replacement = account,
                hosts = HostsCodec.decode(it[HOSTS] ?: ""),
                connections = ConnectionsCodec.decode(it[CONNECTIONS] ?: ""),
            )
            it[HOSTS] = HostsCodec.encode(fleet.hosts)
            it[CONNECTIONS] = ConnectionsCodec.encode(fleet.connections)
            it[RELAY_DOMAIN] = account.relayDomain
            it[RELAY_FLEET_TOKEN] = account.fleetToken
        }
    }

    /** All writes are read-modify-write inside ONE edit transform — DataStore
     *  serializes transforms, so a directory refresh and a manual edit can't
     *  snapshot the same list and lose each other's write. */
    private suspend fun mutate(transform: (List<HostConnection>) -> List<HostConnection>) {
        context.dataStore.edit {
            it[HOSTS] = HostsCodec.encode(transform(HostsCodec.decode(it[HOSTS] ?: "")))
        }
    }

    suspend fun upsert(host: HostConnection) = mutate { upsertHost(it, host) }

    suspend fun upsertAll(hosts: List<HostConnection>) =
        mutate { current -> hosts.fold(current) { acc, h -> upsertHost(acc, h) } }

    suspend fun replaceFromRelay(
        expectedAccount: RelayAccount,
        hosts: List<HostConnection>,
    ): Boolean {
        var applied = false
        context.dataStore.edit {
            if (!relayAccountMatchesExpected(it.relayAccount(), expectedAccount)) return@edit
            val fleet = replaceRelayDirectoryFleet(
                relayDomain = expectedAccount.relayDomain,
                existingHosts = HostsCodec.decode(it[HOSTS] ?: ""),
                replacementHosts = hosts,
                connections = ConnectionsCodec.decode(it[CONNECTIONS] ?: ""),
            )
            it[HOSTS] = HostsCodec.encode(fleet.hosts)
            it[CONNECTIONS] = ConnectionsCodec.encode(fleet.connections)
            applied = true
        }
        return applied
    }

    suspend fun markRelayUnreachable(expectedAccount: RelayAccount): Boolean {
        var applied = false
        context.dataStore.edit {
            if (!relayAccountMatchesExpected(it.relayAccount(), expectedAccount)) return@edit
            val current = HostsCodec.decode(it[HOSTS] ?: "")
            it[HOSTS] = HostsCodec.encode(
                markRelayUnreachable(current, expectedAccount.relayDomain),
            )
            applied = true
        }
        return applied
    }

    /** Persist a direct address only after the caller reached `/health` and
     * `/workspaces` there. A directory row already holding the refresh
     * credential keeps every relay-owned field. */
    suspend fun setDirectUrl(
        hostId: String,
        directUrl: String,
        runnerState: String?,
        contactedAtMillis: Long,
    ) = mutate { current ->
        recordDirectHostDiscovery(
            current,
            hostId,
            directUrl,
            runnerState,
            contactedAtMillis,
        )
    }

    /** Atomically apply one host response only while the account that authorized it is current. */
    suspend fun applyHostWorkspaceRefresh(
        expectedAccount: RelayAccount,
        hostId: String,
        hostBase: String,
        contactedAtMillis: Long,
        discovered: List<WorkspaceConnection>,
        identities: List<VerifiedIdentity>,
    ): Boolean {
        var applied = false
        context.dataStore.edit {
            if (!relayAccountMatchesExpected(it.relayAccount(), expectedAccount)) return@edit
            val currentHosts = HostsCodec.decode(it[HOSTS] ?: "")
            if (currentHosts.none { host ->
                    host.hostId == hostId &&
                        host.relayDomain == expectedAccount.relayDomain
                }
            ) {
                return@edit
            }
            it[HOSTS] = HostsCodec.encode(
                recordHostContact(currentHosts, hostBase, contactedAtMillis),
            )
            val currentConnections = ConnectionsCodec.decode(it[CONNECTIONS] ?: "")
            it[CONNECTIONS] = ConnectionsCodec.encode(
                replaceHostConnections(
                    currentConnections,
                    hostId,
                    discovered,
                    identities,
                ),
            )
            applied = true
        }
        return applied
    }

    /** Every successful request through the shared host-aware client advances
     * the phone's own freshness evidence, regardless of which UI initiated it. */
    suspend fun recordContact(hostBase: String, contactedAtMillis: Long = System.currentTimeMillis()) =
        mutate { current -> recordHostContact(current, hostBase, contactedAtMillis) }

    suspend fun remove(hostId: String) = mutate { list -> list.filterNot { it.hostId == hostId } }
}

private fun androidx.datastore.preferences.core.Preferences.relayAccount(): RelayAccount? {
    val domain = this[RELAY_DOMAIN]?.takeIf { it.isNotBlank() } ?: return null
    val token = this[RELAY_FLEET_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    return RelayAccount(domain, token)
}

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
        onHostContact = { repo.recordContact(it) },
    )
}
