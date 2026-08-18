package com.atomikpanda.groundcontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.NotificationsSetting
import com.atomikpanda.groundcontrol.data.PairLink
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.deriveConnection
import com.atomikpanda.groundcontrol.data.dto.WorkspaceInfo
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(
    private val repo: ConnectionsRepository,
    private val api: SpecApi,
    private val notifications: NotificationsSetting,
) : ViewModel() {
    val connections: StateFlow<List<WorkspaceConnection>> get() = _connections
    private val _connections = MutableStateFlow<List<WorkspaceConnection>>(emptyList())
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init { viewModelScope.launch { repo.connections.collect { _connections.value = it } } }

    val notificationsEnabled: StateFlow<Boolean> get() = notifications.enabled
    fun setNotificationsEnabled(value: Boolean) {
        viewModelScope.launch { notifications.set(value) }
    }

    /** Validate URL, probe /health, persist with the discovered workspace name. */
    fun addOrUpdate(id: String?, baseUrlInput: String, token: String?) {
        val base = normalizedBaseUrl(baseUrlInput) ?: run {
            _testResult.value = "Invalid URL (need http:// or https://)"; return
        }
        val tok = token?.ifBlank { null }
        viewModelScope.launch {
            val probe = WorkspaceConnection(id ?: UUID.randomUUID().toString(), base, tok, "")
            runCatching { api.health(probe).workspace }.fold(
                { name ->
                    repo.upsert(probe.copy(workspaceName = name))
                    _testResult.value = name.ifBlank { "Saved (couldn't reach /health)" }
                },
                {
                    // A daemon HOST's /health doesn't decode as a workspace
                    // HealthResponse. Before the offline-save fallback, check
                    // whether this is a host: saving a host root as a workspace
                    // connection would 404 on every workspace call. If it is,
                    // surface its workspaces for selection instead of saving.
                    val hostWs = runCatching { api.listWorkspaces(base, tok) }.getOrNull()
                    if (hostWs != null) {
                        _discovered.value = DiscoveredWorkspaces(base, tok, hostWs)
                        _testResult.value = "That's a host URL — pick a workspace below"
                    } else {
                        repo.upsert(probe)
                        _testResult.value = "Saved (couldn't reach /health)"
                    }
                },
            )
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
            runCatching { api.listWorkspaces(base, tok) }
                .onSuccess {
                    _discovered.value = DiscoveredWorkspaces(base, tok, it)
                    _testResult.value = "Found ${it.size} workspace(s)"
                }
                .onFailure { _discovered.value = null; _testResult.value = "Couldn't list workspaces: ${it.message}" }
        }
    }

    /** Persist a discovered workspace as a derived connection, using the host
     *  base/token captured at discovery time. The host base URL doubles as the
     *  host handle pre-#471 (opaque resolver seam: literal URL today, relay
     *  identity later — see #471). */
    fun addDiscovered(from: DiscoveredWorkspaces, info: WorkspaceInfo) {
        viewModelScope.launch {
            repo.upsert(
                deriveConnection(
                    hostBase = from.hostBase, hostToken = from.hostToken,
                    hostId = from.hostBase, workspaceId = info.id,
                    workspaceName = info.name, state = info.state,
                )
            )
        }
    }
}
