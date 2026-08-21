package com.atomikpanda.groundcontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceRefreshGeneration
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.LegacyIdentityVerification
import com.atomikpanda.groundcontrol.data.RouteOwnershipSnapshot
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.data.acceptsDirectCredential
import com.atomikpanda.groundcontrol.data.displayLabel
import com.atomikpanda.groundcontrol.data.legacyConnectionsForDiscovery
import com.atomikpanda.groundcontrol.data.unresolvedLegacyConnections
import com.atomikpanda.groundcontrol.data.workspaceRefreshGeneration
import com.atomikpanda.groundcontrol.data.emitAtStaleDeadlines
import com.atomikpanda.groundcontrol.data.hostsFrom
import com.atomikpanda.groundcontrol.data.ladderForHost
import com.atomikpanda.groundcontrol.data.refreshHostWorkspaceConnections
import com.atomikpanda.groundcontrol.data.reachableHostWorkspaces
import com.atomikpanda.groundcontrol.data.verifyLegacyIdentities
import com.atomikpanda.groundcontrol.data.NotificationsSetting
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.VerifiedIdentity
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.deriveConnection
import com.atomikpanda.groundcontrol.data.dto.HostHealth
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.serialization.JsonConvertException
import java.util.UUID

/** One host row for the Settings fleet list. */
data class HostRow(val hostId: String, val label: String, val state: HostLadderState)

/** Time-aware projection used by the ViewModel and deterministic JVM tests. */
internal fun hostRowsFlow(
    hosts: Flow<List<HostConnection>>,
    nowMillis: () -> Long = System::currentTimeMillis,
) = hosts.emitAtStaleDeadlines(nowMillis).map { list ->
    val now = nowMillis()
    list.map { host ->
        HostRow(host.hostId, host.displayLabel(), ladderForHost(host, now))
    }
}

internal data class FleetRefreshFailure(
    val requiresRePair: Boolean,
    val markRelayUnreachable: Boolean,
    val message: String,
)

internal fun classifyFleetRefreshFailure(error: Throwable): FleetRefreshFailure {
    if (error is CancellationException) throw error
    return if (error is JsonConvertException) {
        classifyMalformedFleetDirectoryFailure(error)
    } else if (error is AuthException) {
        FleetRefreshFailure(
            requiresRePair = true,
            markRelayUnreachable = false,
            message = "Re-pair needed — scan the relay account again",
        )
    } else {
        FleetRefreshFailure(
            requiresRePair = false,
            markRelayUnreachable = true,
            message = "Couldn't reach the relay — showing last known hosts",
        )
    }
}

internal fun classifyMalformedFleetDirectoryFailure(error: Throwable): FleetRefreshFailure {
    if (error is CancellationException) throw error
    return FleetRefreshFailure(
        requiresRePair = false,
        markRelayUnreachable = false,
        message = "Relay returned malformed host data — showing last known hosts",
    )
}

internal suspend fun observeRelayAccountChanges(
    accounts: Flow<RelayAccount?>,
    hasHostsForAccount: suspend (RelayAccount) -> Boolean,
    refreshFleet: suspend () -> Unit,
) {
    var initialized = false
    var previous: RelayAccount? = null
    accounts.distinctUntilChanged().collect { account ->
        val shouldRefresh = account != null && (
            (initialized && account != previous) ||
                (!initialized && !hasHostsForAccount(account))
            )
        previous = account
        initialized = true
        if (shouldRefresh) refreshFleet()
    }
}

internal fun visibleSettingsResult(
    message: String,
    owner: RelayAccount?,
    current: RelayAccount?,
): String? = message.takeIf { owner == null || owner == current }

internal fun canAdoptDirectHostIdentity(
    claimedHostId: String?,
    claimedHost: HostConnection?,
): Boolean = claimedHostId?.let { id ->
    id.isNotBlank() &&
        !id.startsWith("http://", ignoreCase = true) &&
        !id.startsWith("https://", ignoreCase = true) &&
        (claimedHost?.let { host ->
            host.hostId == id && host.acceptsDirectCredential()
        } == true)
} == true

internal fun directUrlForDiscovery(
    requestedBase: String,
    reachedBase: String,
): String? {
    val requested = normalizedBaseUrl(requestedBase) ?: return null
    val reached = normalizedBaseUrl(reachedBase) ?: return null
    return reached.takeIf { it == requested }
}

internal fun selectedDiscoveryConnection(
    hostBase: String,
    hostToken: String?,
    adoptedHostId: String?,
    info: WorkspaceInfo,
): WorkspaceConnection {
    val connection = deriveConnection(
        hostBase = hostBase,
        hostToken = hostToken,
        hostId = adoptedHostId ?: hostBase,
        workspaceId = info.id,
        workspaceName = info.name,
        state = info.state,
    )
    return if (adoptedHostId != null) connection else {
        connection.copy(hostId = null, workspaceId = null)
    }
}

internal data class FleetWorkspaceRefreshTarget(
    val host: HostConnection,
    val expectedSourceGeneration: WorkspaceRefreshGeneration,
    val directToken: String?,
) {
    val directOnly: Boolean get() = directToken != null
}

internal data class FleetIdentityVerificationCache(
    val generation: Long,
    val verification: LegacyIdentityVerification,
)

internal fun cacheFleetIdentityVerification(
    expectedGeneration: Long,
    currentGeneration: Long,
    verification: LegacyIdentityVerification,
): FleetIdentityVerificationCache? =
    if (expectedGeneration == currentGeneration) {
        FleetIdentityVerificationCache(expectedGeneration, verification)
    } else {
        null
    }

internal fun FleetIdentityVerificationCache.verificationForGeneration(
    generation: Long,
): LegacyIdentityVerification? = verification.takeIf { this.generation == generation }

internal fun fleetWorkspaceRefreshTarget(
    host: HostConnection,
    hosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
    account: RelayAccount,
    identities: List<VerifiedIdentity>,
    routeOwnershipGeneration: Long,
): FleetWorkspaceRefreshTarget? {
    val generation = workspaceRefreshGeneration(
        routeOwnershipGeneration = routeOwnershipGeneration,
        connections = connections,
        hostId = host.hostId,
        hosts = hosts,
        identities = identities,
    ) ?: return null
    if (host.relayDomain == account.relayDomain) {
        return FleetWorkspaceRefreshTarget(
            host = host,
            expectedSourceGeneration = generation,
            directToken = null,
        )
    }
    if (!host.acceptsDirectCredential()) return null
    val directToken = generation.uniqueCredential ?: return null
    return FleetWorkspaceRefreshTarget(
        host = host,
        expectedSourceGeneration = generation,
        directToken = directToken,
    )
}

internal fun fleetWorkspaceRefreshTargets(
    hosts: List<HostConnection>,
    connections: List<WorkspaceConnection>,
    account: RelayAccount,
    identities: List<VerifiedIdentity>,
    routeOwnershipGeneration: Long,
): List<FleetWorkspaceRefreshTarget> = hosts.mapNotNull { host ->
    fleetWorkspaceRefreshTarget(
        host = host,
        hosts = hosts,
        connections = connections,
        account = account,
        identities = identities,
        routeOwnershipGeneration = routeOwnershipGeneration,
    )
}


private data class SettingsResult(
    val message: String,
    val owner: RelayAccount? = null,
)


class SettingsViewModel(
    private val repo: ConnectionsRepository,
    private val api: SpecApi,
    private val notifications: NotificationsSetting,
    private val hosts: HostsRepository,
) : ViewModel() {
    val connections: StateFlow<List<WorkspaceConnection>> get() = _connections
    private val _connections = MutableStateFlow<List<WorkspaceConnection>>(emptyList())
    private val _testResult = MutableStateFlow<SettingsResult?>(null)
    private val refreshFleetMutex = Mutex()
    val testResult: StateFlow<String?> = combine(
        _testResult,
        hosts.relayAccount,
    ) { result, account ->
        result?.let { visibleSettingsResult(it.message, it.owner, account) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setTestResult(message: String, owner: RelayAccount? = null) {
        _testResult.value = SettingsResult(message, owner)
    }

    init {
        viewModelScope.launch { repo.connections.collect { _connections.value = it } }
        // MainActivity persists relay deep links outside this ViewModel. Observe
        // the repository owner so an already-open Settings screen pulls the new
        // fleet exactly once; equal DataStore emissions must not recurse.
        viewModelScope.launch {
            observeRelayAccountChanges(
                accounts = hosts.relayAccount,
                hasHostsForAccount = { account ->
                    hosts.snapshot().any { it.relayDomain == account.relayDomain }
                },
                refreshFleet = { refreshFleet() },
            )
        }
    }

    val notificationsEnabled: StateFlow<Boolean> get() = notifications.enabled
    fun setNotificationsEnabled(value: Boolean) {
        viewModelScope.launch { notifications.set(value) }
    }

    private fun surfaceRePair(error: Throwable?): Boolean {
        if (error !is RePairNeededException) return false
        _discovered.value = null
        setTestResult("Re-pair needed — scan the relay account again")
        return true
    }
    private fun rejectStaleDiscovery() {
        _discovered.value = null
        setTestResult("Discovery changed — refresh the host and choose again")
    }


    /** Validate URL, probe /health, persist with the discovered workspace name. */
    fun addOrUpdate(id: String?, baseUrlInput: String, token: String?) {
        val base = normalizedBaseUrl(baseUrlInput) ?: run {
            setTestResult("Invalid URL (need http:// or https://)"); return
        }
        val tok = token?.ifBlank { null }
        viewModelScope.launch {
            val probe = WorkspaceConnection(id ?: UUID.randomUUID().toString(), base, tok, "")
            val health = runCatching { api.health(probe).workspace }
            if (health.isSuccess) {
                val name = health.getOrThrow()
                repo.upsert(probe.copy(workspaceName = name))
                setTestResult(name.ifBlank { "Saved (couldn't reach /health)" })
                return@launch
            }
            if (surfaceRePair(health.exceptionOrNull())) return@launch
            // A daemon HOST's /health doesn't decode as a workspace
            // HealthResponse. Before the offline-save fallback, check whether
            // this is a host instead of persisting a root that every call 404s.
            val expectedSnapshot = hosts.routeOwnershipSnapshot()
            val expectedHosts = expectedSnapshot.hosts
            val hostResult = runCatching {
                reachableHostWorkspaces(api, base, tok, expectedHosts)
            }
            if (hostResult.isFailure) {
                if (surfaceRePair(hostResult.exceptionOrNull())) return@launch
                repo.upsert(probe)
                setTestResult("Saved (couldn't reach /health)")
                return@launch
            }
            val (reachedBase, hostWs) = hostResult.getOrThrow()
            val hostHealth = runCatching { api.hostHealth(reachedBase) }
            if (surfaceRePair(hostHealth.exceptionOrNull())) return@launch
            val healthSnapshot = hostHealth.getOrNull()
            _discovered.value = DiscoveredWorkspaces(
                hostBase = reachedBase,
                hostToken = tok,
                workspaces = hostWs,
                hostHealth = healthSnapshot,
                requestedBase = base,
                expectedGeneration = expectedSnapshot.generation,
            )
            setTestResult("That's a host URL — pick a workspace below")
        }
    }

    /**
     * Add a connection from a `groundcontrol://add?...` deep link (scanned QR or tapped URI).
     * Returns false if the link is malformed so the caller can surface "invalid code".
     */
    fun addFromLink(raw: String): Boolean {
        val c = PairLink.parse(raw) ?: return false
        viewModelScope.launch { repo.upsert(c) }
        return true
    }

    fun remove(id: String) { viewModelScope.launch { repo.remove(id) } }

    // ---- Host workspace discovery (#472) ----

    /** A discovery result pinned to the route/ownership generation and host
     * inputs that issued its probe. */
    data class DiscoveredWorkspaces(
        val hostBase: String,
        val hostToken: String?,
        val workspaces: List<WorkspaceInfo>,
        /** This host's own `/health`; null keeps legacy behavior. */
        val hostHealth: HostHealth? = null,
        /** The operator-entered base, retained when [hostBase] is a fallback. */
        val requestedBase: String = hostBase,
        val expectedGeneration: Long,
    ) {
        val hostId: String? get() = hostHealth?.hostId
    }

    private val _discovered = MutableStateFlow<DiscoveredWorkspaces?>(null)
    /** What the last [discoverOnHost] found; null = no discovery ran/failed. */
    val discovered: StateFlow<DiscoveredWorkspaces?> = _discovered.asStateFlow()

    /** Probe {host}/workspaces and surface the list for selection. Degraded
     *  entries are kept (with their state) so the operator can see them. */
    fun discoverOnHost(baseUrlInput: String, token: String?) {
        val base = normalizedBaseUrl(baseUrlInput) ?: run {
            setTestResult("Invalid URL (need http:// or https://)"); return
        }
        val tok = token?.ifBlank { null }
        viewModelScope.launch {
            val expectedSnapshot = hosts.routeOwnershipSnapshot()
            val expectedHosts = expectedSnapshot.hosts
            val result = runCatching {
                reachableHostWorkspaces(api, base, tok, expectedHosts)
            }
            if (result.isFailure) {
                _discovered.value = null
                if (!surfaceRePair(result.exceptionOrNull())) {
                    setTestResult("Couldn't list workspaces: ${result.exceptionOrNull()?.message}")
                }
                return@launch
            }
            val (reachedBase, workspaces) = result.getOrThrow()
            val hostHealth = runCatching { api.hostHealth(reachedBase) }
            if (surfaceRePair(hostHealth.exceptionOrNull())) return@launch
            val healthSnapshot = hostHealth.getOrNull()
            _discovered.value = DiscoveredWorkspaces(
                hostBase = reachedBase,
                hostToken = tok,
                workspaces = workspaces,
                hostHealth = healthSnapshot,
                requestedBase = base,
                expectedGeneration = expectedSnapshot.generation,
            )
            setTestResult("Found ${workspaces.size} workspace(s)")
        }
    }

    // ---- relay account: the fleet, not an address (#471) --------------------

    val relayAccount: StateFlow<RelayAccount?> =
        hosts.relayAccount.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hostRows: StateFlow<List<HostRow>> = hostRowsFlow(hosts.hosts)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Store a scanned `groundcontrol://add-relay?...` account and pull the fleet.
     * Returns false on a malformed link so the caller can say "invalid code".
     */
    fun addRelayFromLink(raw: String): Boolean {
        val account = PairLink.parseRelay(raw) ?: return false
        viewModelScope.launch { hosts.setRelayAccount(account) }
        return true
    }

    /** Re-read the directory, then each reachable host's workspaces. */
    fun refreshFleetNow() { viewModelScope.launch { refreshFleet() } }

    private suspend fun refreshFleet() = refreshFleetMutex.withLock {
        val expectedSnapshot = hosts.routeOwnershipSnapshot()
        val account = expectedSnapshot.account ?: run {
            setTestResult("No relay account paired yet")
            return
        }
        var entryCount = 0
        val entries = try {
            api.listHosts(account.relayDomain, account.fleetToken)
        } catch (error: Exception) {
            val failure = classifyFleetRefreshFailure(error)
            val current = if (failure.markRelayUnreachable) {
                hosts.markRelayUnreachable(account, expectedSnapshot.generation)
            } else {
                hosts.routeOwnershipSnapshot().generation == expectedSnapshot.generation
            }
            if (current) setTestResult(failure.message, account)
            return
        }
        entryCount = entries.size
        val incoming = try {
            hostsFrom(entries, account.relayDomain)
        } catch (error: Exception) {
            val failure = classifyMalformedFleetDirectoryFailure(error)
            if (hosts.routeOwnershipSnapshot().generation == expectedSnapshot.generation) {
                setTestResult(failure.message, account)
            }
            return
        }
        if (
            !hosts.replaceFromRelay(
                expectedAccount = account,
                expectedGeneration = expectedSnapshot.generation,
                hosts = incoming,
            )
        ) {
            return
        }
        setTestResult("Fleet: $entryCount host(s)", account)
        val directorySnapshot = hosts.routeOwnershipSnapshot()
        // Freeze only the deterministic worklist. Each network request gets a
        // fresh atomic target so a prior host's accepted route write can advance
        // the generation without making every remaining host stale.
        val hostIds = directorySnapshot.hosts.map { it.hostId }.distinct()
        var identityCache: FleetIdentityVerificationCache? = null
        suspend fun snapshotWithVerifiedIdentities():
            Pair<RouteOwnershipSnapshot, LegacyIdentityVerification>? {
            while (true) {
                val snapshot = hosts.routeOwnershipSnapshot()
                if (snapshot.account != account) return null
                val cached = identityCache?.verificationForGeneration(snapshot.generation)
                if (cached != null) return snapshot to cached
                val verification = verifyLegacyIdentities(
                    api,
                    unresolvedLegacyConnections(snapshot.connections),
                    snapshot.hosts,
                )
                val current = hosts.routeOwnershipSnapshot()
                val accepted = cacheFleetIdentityVerification(
                    expectedGeneration = snapshot.generation,
                    currentGeneration = current.generation,
                    verification = verification,
                ) ?: continue
                identityCache = accepted
                return snapshot to verification
            }
        }
        for (hostId in hostIds) {
            val (targetSnapshot, verification) =
                snapshotWithVerifiedIdentities() ?: return
            if (verification.requiresRePair) {
                setTestResult("Re-pair needed — scan the relay account again", account)
            }
            val identities = verification.identities
            val currentHost = targetSnapshot.hosts.singleOrNull { it.hostId == hostId }
                ?: continue
            val target = fleetWorkspaceRefreshTarget(
                host = currentHost,
                hosts = targetSnapshot.hosts,
                connections = targetSnapshot.connections,
                account = account,
                identities = identities,
                routeOwnershipGeneration = targetSnapshot.generation,
            ) ?: continue
            val host = target.host
            val refreshed = try {
                refreshHostWorkspaceConnections(
                    api,
                    host,
                    identities,
                    directToken = target.directToken,
                )
            } catch (_: RePairNeededException) {
                setTestResult("Re-pair needed — scan the relay account again", account)
                continue
            } catch (_: AuthException) {
                continue
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            } ?: continue
            if (!hosts.applyHostWorkspaceRefresh(
                    hostId = host.hostId,
                    hostBase = refreshed.hostBase,
                    expectedDirectOnly = target.directOnly,
                    expectedSourceGeneration = target.expectedSourceGeneration,
                    contactedAtMillis = System.currentTimeMillis(),
                    discovered = refreshed.connections,
                    identities = refreshed.identities,
                )
            ) {
                return
            }
        }
    }


    /** Persist a selected direct-host workspace. Only a current persisted
     * direct host can supply fleet identity; every other discovery remains an
     * unowned direct connection with its own base and credential. */
    fun addDiscovered(from: DiscoveredWorkspaces, info: WorkspaceInfo) {
        viewModelScope.launch {
            val ownerSnapshot = hosts.routeOwnershipSnapshot()
            if (ownerSnapshot.generation != from.expectedGeneration) {
                rejectStaleDiscovery()
                return@launch
            }
            val initialHosts = ownerSnapshot.hosts

            val claimedHost = from.hostId?.let { claimed ->
                initialHosts.singleOrNull { it.hostId == claimed }
            }
            val canAdoptClaimedHost =
                canAdoptDirectHostIdentity(from.hostId, claimedHost)
            val adoptedHostId = from.hostId.takeIf { canAdoptClaimedHost }
            val discovered = selectedDiscoveryConnection(
                hostBase = from.hostBase,
                hostToken = from.hostToken,
                adoptedHostId = adoptedHostId,
                info = info,
            )
            val discoveryHostBases = listOf(from.requestedBase, from.hostBase)
            val legacyRows = legacyConnectionsForDiscovery(
                connections = ownerSnapshot.connections,
                hostBases = discoveryHostBases,
                workspaceId = info.id,
            )
            val identities = verifyLegacyIdentities(api, legacyRows, initialHosts).identities
            val applied = hosts.applyDiscoveredWorkspace(
                expectedGeneration = from.expectedGeneration,
                directHostId = adoptedHostId,
                discovered = discovered,
                identities = identities,
                activatePriorDirectToken = true,
                directUrl = directUrlForDiscovery(from.requestedBase, from.hostBase)
                    .takeIf { canAdoptClaimedHost },
                runnerState = from.hostHealth?.runner?.state ?: info.runner?.state,
                contactedAtMillis = System.currentTimeMillis(),
            )
            if (!applied) {
                rejectStaleDiscovery()
                return@launch
            }
            if (!canAdoptClaimedHost && from.hostId != null) {
                setTestResult(
                    "Direct identity is unverified; saved as a separate connection",
                )
            }
        }
    }
}
