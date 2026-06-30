package com.atomikpanda.groundcontrol.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val context = LocalContext.current
    val connections by vm.connections.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    val notificationsOn by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setNotificationsEnabled(true)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (contents == null) return@rememberLauncherForActivityResult // user cancelled
        val ok = vm.addFromLink(contents)
        Toast.makeText(
            context,
            if (ok) "Connection added" else "Invalid code",
            Toast.LENGTH_SHORT,
        ).show()
    }

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
        Button(
            onClick = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan a Ground Control pairing QR")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
        ) { Text("Scan QR") }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ListItem(
            headlineContent = { Text("Notifications") },
            supportingContent = { Text("Alert me when an agent needs me (all workspaces)") },
            trailingContent = {
                Switch(checked = notificationsOn, onCheckedChange = { want ->
                    if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                    ) {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setNotificationsEnabled(want)
                    }
                })
            },
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
