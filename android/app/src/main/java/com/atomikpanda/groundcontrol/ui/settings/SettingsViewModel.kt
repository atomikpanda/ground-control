package com.atomikpanda.groundcontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.RePairNeededException
import com.atomikpanda.groundcontrol.data.displayLabel
import com.atomikpanda.groundcontrol.data.hostFrom
import com.atomikpanda.groundcontrol.data.ladderForHost
import com.atomikpanda.groundcontrol.data.refreshHostWorkspaceConnections
import com.atomikpanda.groundcontrol.data.NotificationsSetting
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.deriveConnection
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(
    private val repo: ConnectionsRepository,
    private val api: SpecApi,
    private val notifications: NotificationsSetting,
    private val hosts: HostsRepository,
) : ViewModel() {
    val connections: StateFlow<List<WorkspaceConnection>> get() = _connections
    private val _connections = MutableStateFlow<List<WorkspaceConnection>>(emptyList())
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init {
        viewModelScope.launch { repo.connections.collect { _connections.value = it } }
        // A relay account paired by deep link (not through this screen) has no
        // fleet yet — pull it once so the hosts appear without a manual refresh.
        viewModelScope.launch {
            if (hosts.relayAccountSnapshot() != null && hosts.snapshot().isEmpty()) refreshFleet()
        }
    }

    val notificationsEnabled: StateFlow<Boolean> get() = notifications.enabled
    fun setNotificationsEnabled(value: Boolean) {
        viewModelScope.launch { notifications.set(value) }
    }

    private fun surfaceRePair(error: Throwable?): Boolean {
        if (error !is RePairNeededException) return false
        _discovered.value = null
        _testResult.value = "Re-pair needed — scan the relay account again"
        return true
    }

    /** Validate URL, probe /health, persist with the discovered workspace name. */
    fun addOrUpdate(id: String?, baseUrlInput: String, token: String?) {
        val base = normalizedBaseUrl(baseUrlInput) ?: run {
            _testResult.value = "Invalid URL (need http:// or https://)"; return
        }
        val tok = token?.ifBlank { null }
        viewModelScope.launch {
            val probe = WorkspaceConnection(id ?: UUID.randomUUID().toString(), base, tok, "")
            val health = runCatching { api.health(probe).workspace }
            if (health.isSuccess) {
                val name = health.getOrThrow()
                repo.upsert(probe.copy(workspaceName = name))
                _testResult.value = name.ifBlank { "Saved (couldn't reach /health)" }
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
                _testResult.value = "Saved (couldn't reach /health)"
                return@launch
            }
            val identity = runCatching { api.hostHealth(base).hostId }
            if (surfaceRePair(identity.exceptionOrNull())) return@launch
            val hostWs = hostResult.getOrThrow()
            _discovered.value = DiscoveredWorkspaces(base, tok, hostWs, identity.getOrNull())
            _testResult.value = "That's a host URL — pick a workspace below"
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
            _testResult.value = "Invalid URL (need http:// or https://)"; return
        }
        val tok = token?.ifBlank { null }
        viewModelScope.launch {
            val result = runCatching { api.listWorkspaces(base, tok) }
            if (result.isFailure) {
                _discovered.value = null
                if (!surfaceRePair(result.exceptionOrNull())) {
                    _testResult.value = "Couldn't list workspaces: ${result.exceptionOrNull()?.message}"
                }
                return@launch
            }
            val identity = runCatching { api.hostHealth(base).hostId }
            if (surfaceRePair(identity.exceptionOrNull())) return@launch
            val workspaces = result.getOrThrow()
            _discovered.value = DiscoveredWorkspaces(base, tok, workspaces, identity.getOrNull())
            _testResult.value = "Found ${workspaces.size} workspace(s)"
        }
    }

    // ---- relay account: the fleet, not an address (#471) --------------------

    /** One host row for the Settings fleet list. */
    data class HostRow(val hostId: String, val label: String, val state: HostLadderState)

    val relayAccount: StateFlow<RelayAccount?> =
        hosts.relayAccount.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hostRows: StateFlow<List<HostRow>> = hosts.hosts
        .map { list ->
            val now = System.currentTimeMillis()
            list.map { h ->
                HostRow(h.hostId, h.displayLabel(), ladderForHost(h, now))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Store a scanned `groundcontrol://add-relay?...` account and pull the fleet.
     * Returns false on a malformed link so the caller can say "invalid code".
     */
    fun addRelayFromLink(raw: String): Boolean {
        val account = PairLink.parseRelay(raw) ?: return false
        viewModelScope.launch {
            hosts.setRelayAccount(account)
            refreshFleet()
        }
        return true
    }

    /** Re-read the directory, then each reachable host's workspaces. */
    fun refreshFleetNow() { viewModelScope.launch { refreshFleet() } }

    private suspend fun refreshFleet() {
        val account = hosts.relayAccountSnapshot() ?: run {
            _testResult.value = "No relay account paired yet"; return
        }
        val before = hosts.snapshot().map { it.hostId }.toSet()
        val directory = runCatching { api.listHosts(account.relayDomain, account.fleetToken) }
        val newHostIds = directory.fold(
            onSuccess = { entries ->
                val incoming = entries.mapNotNull { hostFrom(it, account.relayDomain) }
                hosts.replaceFromRelay(account.relayDomain, incoming)
                _testResult.value = "Fleet: ${entries.size} host(s)"
                incoming.map { it.hostId }.filterNotTo(mutableSetOf()) { it in before }
            },
            onFailure = {
                // Missing directory state is its own conservative ladder outcome;
                // keep cached addresses and refresh credentials for direct reads.
                hosts.markRelayUnreachable(account.relayDomain)
                _testResult.value = "Couldn't reach the relay — showing last known hosts"
                emptySet()
            },
        )
        // A newly added host gets one verified adoption pass; ordinary refreshes
        // only re-key discovered rows on their established identity tuple.
        val legacyConnections = if (newHostIds.isEmpty()) emptyList() else repo.snapshot()
        for (host in hosts.snapshot()) {
            val refreshed = try {
                refreshHostWorkspaceConnections(
                    api,
                    host,
                    legacyConnections.takeIf { host.hostId in newHostIds }.orEmpty(),
                )
            } catch (_: RePairNeededException) {
                _testResult.value = "Re-pair needed — scan the relay account again"
                continue
            } ?: continue
            hosts.upsert(host.copy(lastContactAtMillis = System.currentTimeMillis()))
            if (host.hostId in newHostIds) repo.adopt(refreshed.connections, refreshed.identities)
            else repo.upsertAll(refreshed.connections)
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
                hosts.setDirectUrl(hostId, from.hostBase, System.currentTimeMillis())
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
