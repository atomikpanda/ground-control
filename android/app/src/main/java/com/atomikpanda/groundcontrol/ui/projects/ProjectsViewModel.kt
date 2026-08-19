package com.atomikpanda.groundcontrol.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.HostConnection
import com.atomikpanda.groundcontrol.data.HostLadderState
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.emitAtStaleDeadlines
import com.atomikpanda.groundcontrol.data.hasStableIdentityTuple
import com.atomikpanda.groundcontrol.data.ladderFor
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import com.atomikpanda.groundcontrol.ui.theme.resolveIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One Projects-tab row: name + resolved identity + the reused workspace detail route. */
data class ProjectRow(
    val connectionId: String,
    val name: String,
    val identity: WorkspaceIdentity,
    val route: String,
    /** The one ladder's verdict (#471); null for a manually paired row, which has
     *  no host and therefore nothing the ladder can honestly say. */
    val state: HostLadderState? = null,
)

/** Reuse the existing per-workspace detail route (GroundControlApp `workspace/{connectionId}`). */
fun workspaceRoute(connectionId: String): String = "workspace/$connectionId"

/** Pure: one row per connection, identity resolved override-or-auto. No I/O (ac8: offline). */
fun projectRows(
    connections: List<WorkspaceConnection>,
    // Defaulted because a phone with no relay account has no hosts at all, and
    // then the clock is unused: every row is manual, and the ladder says nothing.
    hosts: List<HostConnection> = emptyList(),
    nowMillis: Long = 0L,
): List<ProjectRow> =
    connections.map { c ->
        ProjectRow(
            connectionId = c.id,
            name = c.workspaceName.ifBlank { c.baseUrl },
            identity = resolveIdentity(c),
            route = workspaceRoute(c.id),
            state = if (c.hasStableIdentityTuple()) {
                ladderFor(c, hosts.firstOrNull { it.hostId == c.hostId }, nowMillis)
            } else {
                null
            },
        )
    }

/** Time-aware projection used by the ViewModel and deterministic JVM tests. */
internal fun projectRowsFlow(
    connections: Flow<List<WorkspaceConnection>>,
    hosts: Flow<List<HostConnection>>,
    nowMillis: () -> Long = System::currentTimeMillis,
) = connections.combine(hosts.emitAtStaleDeadlines(nowMillis)) { conns, hs ->
    projectRows(conns, hs, nowMillis())
}

class ProjectsViewModel(
    private val repo: ConnectionsRepository,
    private val hostsRepo: HostsRepository,
) : ViewModel() {
    val rows: StateFlow<List<ProjectRow>> get() = _rows
    private val _rows = MutableStateFlow<List<ProjectRow>>(emptyList())

    init {
        viewModelScope.launch {
            projectRowsFlow(repo.connections, hostsRepo.hosts)
                .collect { _rows.value = it }
        }
    }

    /** Persist an operator override (null clears → auto). */
    fun setOverride(connectionId: String, colorOverride: String?, glyphOverride: String?) {
        viewModelScope.launch { repo.setIdentity(connectionId, colorOverride, glyphOverride) }
    }
}
