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
        viewModelScope.launch {
            val probe = WorkspaceConnection(id ?: UUID.randomUUID().toString(), base, token?.ifBlank { null }, "")
            val named = runCatching { api.health(probe).workspace }
                .fold({ probe.copy(workspaceName = it) }, { probe })
            repo.upsert(named)
            _testResult.value = named.workspaceName.ifBlank { "Saved (couldn't reach /health)" }
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

    private val _discovered = MutableStateFlow<List<WorkspaceInfo>?>(null)
    /** Workspaces the last [discoverOnHost] found; null = no discovery ran/failed. */
    val discovered: StateFlow<List<WorkspaceInfo>?> = _discovered.asStateFlow()

    /** Probe {host}/workspaces and surface the list for selection. Degraded
     *  entries are kept (with their state) so the operator can see them. */
    fun discoverOnHost(baseUrlInput: String, token: String?) {
        val base = normalizedBaseUrl(baseUrlInput) ?: run {
            _testResult.value = "Invalid URL (need http:// or https://)"; return
        }
        viewModelScope.launch {
            runCatching { api.listWorkspaces(base, token?.ifBlank { null }) }
                .onSuccess { _discovered.value = it; _testResult.value = "Found ${it.size} workspace(s)" }
                .onFailure { _discovered.value = null; _testResult.value = "Couldn't list workspaces: ${it.message}" }
        }
    }

    /** Persist a discovered workspace as a derived connection. The host base
     *  URL doubles as the host handle pre-#471 (opaque resolver seam: literal
     *  URL today, relay identity later — see #471). */
    fun addDiscovered(baseUrlInput: String, token: String?, info: WorkspaceInfo) {
        val base = normalizedBaseUrl(baseUrlInput) ?: return
        viewModelScope.launch {
            repo.upsert(
                deriveConnection(
                    hostBase = base, hostToken = token?.ifBlank { null },
                    hostId = base, workspaceId = info.id,
                    workspaceName = info.name, state = info.state,
                )
            )
        }
    }
}
