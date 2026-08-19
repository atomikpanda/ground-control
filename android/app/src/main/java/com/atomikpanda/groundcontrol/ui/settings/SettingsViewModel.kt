package com.atomikpanda.groundcontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.data.displayLabel
import com.atomikpanda.groundcontrol.data.emitAtStaleDeadlines
import com.atomikpanda.groundcontrol.data.hostFrom
import com.atomikpanda.groundcontrol.data.ladderForHost
import com.atomikpanda.groundcontrol.data.refreshHostWorkspaceConnections
import com.atomikpanda.groundcontrol.data.unresolvedLegacyConnections
import com.atomikpanda.groundcontrol.data.verifyLegacyIdentities
import com.atomikpanda.groundcontrol.data.NotificationsSetting
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.deriveConnection
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val message: String,
)

internal fun classifyFleetRefreshFailure(error: Throwable): FleetRefreshFailure =
    if (error is AuthException) {
        FleetRefreshFailure(
            requiresRePair = true,
            message = "Re-pair needed — scan the relay account again",
        )
    } else {
        FleetRefreshFailure(
            requiresRePair = false,
            message = "Couldn't reach the relay — showing last known hosts",
        )
    }

internal suspend fun observeRelayAccountChanges(
    accounts: Flow<RelayAccount?>,
    hasHosts: suspend () -> Boolean,
    refreshFleet: suspend () -> Unit,
) {
    var initialized = false
    var previous: RelayAccount? = null
    accounts.distinctUntilChanged().collect { account ->
        val shouldRefresh = account != null && (
            (initialized && account != previous) ||
                (!initialized && !hasHosts())
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
                hasHosts = { hosts.snapshot().isNotEmpty() },
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
            val hostResult = runCatching { api.listWorkspaces(base, tok) }
            if (hostResult.isFailure) {
                if (surfaceRePair(hostResult.exceptionOrNull())) return@launch
                repo.upsert(probe)
                setTestResult("Saved (couldn't reach /health)")
                return@launch
            }
            val identity = runCatching { api.hostHealth(base).hostId }
            if (surfaceRePair(identity.exceptionOrNull())) return@launch
            val hostWs = hostResult.getOrThrow()
            _discovered.value = DiscoveredWorkspaces(base, tok, hostWs, identity.getOrNull())
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

    /** A discovery result pinned to the host base/token the probe actually
     *  used, so a later edit of the input fields (or a stale response landing
     *  after a newer host was entered) can't persist a workspace under the
     *  wrong host or token. */
    data class DiscoveredWorkspaces(
        val hostBase: String,
        val hostToken: String?,
        val workspaces: List<WorkspaceInfo>,
        /** Verified by this host's own `/health`; null keeps legacy behavior. */
        val hostId: String? = null,
    )

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
            val result = runCatching { api.listWorkspaces(base, tok) }
            if (result.isFailure) {
                _discovered.value = null
                if (!surfaceRePair(result.exceptionOrNull())) {
                    setTestResult("Couldn't list workspaces: ${result.exceptionOrNull()?.message}")
                }
                return@launch
            }
            val identity = runCatching { api.hostHealth(base).hostId }
            if (surfaceRePair(identity.exceptionOrNull())) return@launch
            val workspaces = result.getOrThrow()
            _discovered.value = DiscoveredWorkspaces(base, tok, workspaces, identity.getOrNull())
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

    private suspend fun refreshFleet() {
        val account = hosts.relayAccountSnapshot() ?: run {
            setTestResult("No relay account paired yet")
            return
        }
        val entries = try {
            api.listHosts(account.relayDomain, account.fleetToken)
        } catch (error: Exception) {
            val failure = classifyFleetRefreshFailure(error)
            val current = if (failure.requiresRePair) {
                hosts.relayAccountSnapshot() == account
            } else {
                hosts.markRelayUnreachable(account)
            }
            if (current) setTestResult(failure.message, account)
            return
        }
        val incoming = entries.mapNotNull { hostFrom(it, account.relayDomain) }
        if (!hosts.replaceFromRelay(account, incoming)) return
        setTestResult("Fleet: ${entries.size} host(s)", account)

        val verification = verifyLegacyIdentities(
            api,
            unresolvedLegacyConnections(repo.snapshot()),
        )
        if (verification.requiresRePair) {
            setTestResult("Re-pair needed — scan the relay account again", account)
        }
        val identities = verification.identities
        for (host in hosts.snapshot().filter { it.relayDomain == account.relayDomain }) {
            val refreshed = try {
                refreshHostWorkspaceConnections(api, host, identities)
            } catch (_: RePairNeededException) {
                setTestResult("Re-pair needed — scan the relay account again", account)
                continue
            } ?: continue
            if (!hosts.applyHostWorkspaceRefresh(
                    expectedAccount = account,
                    hostId = host.hostId,
                    hostBase = refreshed.hostBase,
                    contactedAtMillis = System.currentTimeMillis(),
                    discovered = refreshed.connections,
                    identities = refreshed.identities,
                )
            ) {
                return
            }
        }
    }


    /** Persist a selected direct-host workspace. When `/health` verified a real
     * host id, the reachable LAN/tailnet address is attached to that host and
     * will carry forward when the relay later supplies its refresh credential.
     * A legacy host without identity keeps the pre-#471 URL handle and token. */
    fun addDiscovered(from: DiscoveredWorkspaces, info: WorkspaceInfo) {
        viewModelScope.launch {
            val hostId = from.hostId ?: from.hostBase
            if (from.hostId != null) {
                hosts.setDirectUrl(
                    hostId,
                    from.hostBase,
                    info.runner?.state,
                    System.currentTimeMillis(),
                )
            }
            val storedHost = hosts.snapshot().firstOrNull { it.hostId == hostId }
            repo.upsert(
                deriveConnection(
                    hostBase = from.hostBase,
                    hostToken = from.hostToken.takeIf { storedHost?.refresh == null },
                    hostId = hostId,
                    workspaceId = info.id,
                    workspaceName = info.name,
                    state = info.state,
                ),
            )
        }
    }
}
