package com.atomikpanda.groundcontrol.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.ui.components.WorkspaceBadge

@Composable
fun ProjectsScreen(
    vm: ProjectsViewModel,
    onOpenWorkspace: (connectionId: String) -> Unit,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.connectionId }) { row ->
            ListItem(
                leadingContent = { WorkspaceBadge(row.identity, size = 32.dp) },
                headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.clickable { onOpenWorkspace(row.connectionId) },
            )
        }
    }
}
