package com.atomikpanda.groundcontrol

import android.content.Context
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.defaultHttpClient
import com.atomikpanda.groundcontrol.ui.nav.Section
import com.atomikpanda.groundcontrol.ui.placeholder.PlaceholderScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsViewModel
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxScreen
import com.atomikpanda.groundcontrol.ui.specs.SpecInboxViewModel
import kotlinx.coroutines.runBlocking

@Composable
fun GroundControlApp(context: Context) {
    val nav = rememberNavController()
    val connRepo = remember { ConnectionsRepository(context.applicationContext) }
    val api = remember { SpecApi(defaultHttpClient()) }
    val specRepo = remember { SpecRepository(api) }

    Scaffold(bottomBar = {
        val current by nav.currentBackStackEntryAsState()
        NavigationBar {
            Section.entries.forEach { s ->
                NavigationBarItem(
                    selected = current?.destination?.route == s.route,
                    onClick = { nav.navigate(s.route) { launchSingleTop = true } },
                    icon = { Icon(s.icon, s.label) },
                    label = { Text(s.label) },
                )
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = Section.SPECS.route, modifier = Modifier.padding(padding)) {
            composable(Section.SPECS.route) {
                val vm = viewModel {
                    SpecInboxViewModel(specRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                SpecInboxScreen(vm)
            }
            composable(Section.CAPTURE.route) { PlaceholderScreen("Capture", "C3") }
            composable(Section.DECISIONS.route) { PlaceholderScreen("Decisions", "C7") }
            composable(Section.TASKS.route) { PlaceholderScreen("Tasks", "C7") }
            composable(Section.SETTINGS.route) {
                val vm = viewModel { SettingsViewModel(connRepo, api) }
                SettingsScreen(vm)
            }
        }
    }
}

/** Bridge the suspend snapshot to the VM's sync provider on first refresh. */
private fun runBlockingSnapshot(repo: ConnectionsRepository): List<WorkspaceConnection> =
    runBlocking { repo.snapshot() }
