package com.atomikpanda.groundcontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(
    private val repo: ConnectionsRepository,
    private val api: SpecApi,
) : ViewModel() {
    val connections: StateFlow<List<WorkspaceConnection>> get() = _connections
    private val _connections = MutableStateFlow<List<WorkspaceConnection>>(emptyList())
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init { viewModelScope.launch { repo.connections.collect { _connections.value = it } } }

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

    fun remove(id: String) { viewModelScope.launch { repo.remove(id) } }
}
