package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import kotlinx.coroutines.flow.MutableStateFlow

internal fun connectionState(connections: List<WorkspaceConnection>) =
    MutableStateFlow<ConnectionState>(ConnectionState.Ready(connections))
