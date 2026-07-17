package com.atomikpanda.groundcontrol.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import com.atomikpanda.groundcontrol.ui.theme.resolveIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One Projects-tab row: name + resolved identity + the reused workspace detail route. */
data class ProjectRow(
    val connectionId: String,
    val name: String,
    val identity: WorkspaceIdentity,
    val route: String,
)

/** Reuse the existing per-workspace detail route (GroundControlApp `workspace/{connectionId}`). */
fun workspaceRoute(connectionId: String): String = "workspace/$connectionId"

/** Pure: one row per connection, identity resolved override-or-auto. No I/O (ac8: offline). */
fun projectRows(connections: List<WorkspaceConnection>): List<ProjectRow> =
    connections.map { c ->
        ProjectRow(
            connectionId = c.id,
            name = c.workspaceName.ifBlank { c.baseUrl },
            identity = resolveIdentity(c),
            route = workspaceRoute(c.id),
        )
    }

class ProjectsViewModel(private val repo: ConnectionsRepository) : ViewModel() {
    val rows: StateFlow<List<ProjectRow>> get() = _rows
    private val _rows = MutableStateFlow<List<ProjectRow>>(emptyList())

    init { viewModelScope.launch { repo.connections.collect { _rows.value = projectRows(it) } } }

    /** Persist an operator override (null clears → auto). */
    fun setOverride(connectionId: String, colorOverride: String?, glyphOverride: String?) {
        viewModelScope.launch { repo.setIdentity(connectionId, colorOverride, glyphOverride) }
    }
}
