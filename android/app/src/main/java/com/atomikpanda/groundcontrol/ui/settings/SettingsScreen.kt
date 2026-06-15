package com.atomikpanda.groundcontrol.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceConnection

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val connections by vm.connections.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Workspace connections", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("mship serve URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Bearer token (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.addOrUpdate(null, url, token); url = ""; token = "" }) { Text("Add / test") }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(connections, key = { it.id }) { c: WorkspaceConnection ->
                ListItem(
                    headlineContent = { Text(c.workspaceName.ifBlank { c.baseUrl }) },
                    supportingContent = { Text(c.baseUrl) },
                    trailingContent = { TextButton(onClick = { vm.remove(c.id) }) { Text("Remove") } },
                )
            }
        }
    }
}
