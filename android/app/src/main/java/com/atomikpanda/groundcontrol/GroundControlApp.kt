package com.atomikpanda.groundcontrol

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.DataStoreNotificationsSetting
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.defaultHttpClient
import com.atomikpanda.groundcontrol.ui.home.HomeScreen
import com.atomikpanda.groundcontrol.ui.home.HomeViewModel
import com.atomikpanda.groundcontrol.ui.messages.ConversationScreen
import com.atomikpanda.groundcontrol.ui.messages.ConversationViewModel
import com.atomikpanda.groundcontrol.ui.messages.MessagesScreen
import com.atomikpanda.groundcontrol.ui.messages.MessagesUiState
import com.atomikpanda.groundcontrol.ui.messages.MessagesViewModel
import com.atomikpanda.groundcontrol.ui.messages.NewThreadScreen
import com.atomikpanda.groundcontrol.ui.messages.NewThreadViewModel
import com.atomikpanda.groundcontrol.ui.nav.Section
import com.atomikpanda.groundcontrol.ui.queue.QueueScreen
import com.atomikpanda.groundcontrol.ui.queue.QueueViewModel
import com.atomikpanda.groundcontrol.ui.console.ConsoleScreen
import com.atomikpanda.groundcontrol.ui.console.ConsoleViewModel
import com.atomikpanda.groundcontrol.ui.done.DoneScreen
import com.atomikpanda.groundcontrol.ui.done.DoneViewModel
import com.atomikpanda.groundcontrol.ui.farm.FarmScreen
import com.atomikpanda.groundcontrol.ui.farm.FarmViewModel
import com.atomikpanda.groundcontrol.ui.review.ReviewScreen
import com.atomikpanda.groundcontrol.ui.review.ReviewViewModel
import com.atomikpanda.groundcontrol.ui.settings.SettingsScreen
import com.atomikpanda.groundcontrol.ui.settings.SettingsViewModel
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailScreen
import com.atomikpanda.groundcontrol.ui.specdetail.SpecDetailViewModel
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailScreen
import com.atomikpanda.groundcontrol.ui.tasks.TaskDetailViewModel
import com.atomikpanda.groundcontrol.ui.tasks.TasksScreen
import com.atomikpanda.groundcontrol.ui.tasks.TasksViewModel
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceScreen
import com.atomikpanda.groundcontrol.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.runBlocking

@Composable
fun GroundControlApp(
    context: Context,
    pendingThread: MutableStateFlow<Pair<String, String>?>? = null,
) {
    val nav = rememberNavController()
    val connRepo = remember { ConnectionsRepository(context.applicationContext) }
    val api = remember { SpecApi(defaultHttpClient()) }
    val homeRepo = remember { HomeFeedRepository(api) }
    val queueRepo = remember { QueueRepository(api) }
    val detailRepo = remember { SpecDetailRepository(api) }
    val tasksRepo = remember { TasksRepository(api) }
    val threadsRepo = remember { ThreadsRepository(api) }
    // Composition-scoped (cancelled on disposal) — not MainScope(), which would leak its
    // stateIn collector across Activity recreations.
    val appScope = rememberCoroutineScope()
    val notificationsSetting = remember { DataStoreNotificationsSetting(context.applicationContext, appScope) }
    // Activity-scoped (not per-NavBackStackEntry): shared by the Home sticky threads card and the
    // "threads" drill-in list so the loaded sections + live-poll loop survive navigating between
    // them (spec: ground-control-thread-findability).
    val messagesVm = viewModel {
        MessagesViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
    }

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
        NavHost(nav, startDestination = Section.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Section.HOME.route) {
                val vm = viewModel {
                    HomeViewModel(homeRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                HomeScreen(
                    vm,
                    messagesVm,
                    onApproval = { connId, specId -> nav.navigate("specDetail/$connId/$specId") },
                    onQuestion = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onBlocker = { connId, slug -> nav.navigate("taskDetail/$connId/$slug") },
                    onBrowseWorkspace = { connId -> nav.navigate("farm/$connId") },
                    onCapture = { nav.navigate("capture") },
                    onOpenThreads = { nav.navigate("threads") },
                )
            }
            composable(Section.QUEUE.route) {
                val vm = viewModel {
                    QueueViewModel(queueRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                val uriHandler = LocalUriHandler.current
                QueueScreen(
                    vm,
                    onOpenItem = { connId, itemId -> nav.navigate("item/$connId/$itemId") },
                    onOpenPr = { url -> uriHandler.openUri(url) },
                )
            }
            composable("threads") {
                MessagesScreen(
                    messagesVm,
                    onThreadClick = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onNewThread = {
                        val connId = (messagesVm.state.value as? MessagesUiState.Content)?.selectedConnectionId
                        nav.navigate(if (connId != null) "newThread?connectionId=$connId" else "newThread")
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Section.TASKS.route) {
                val vm = viewModel {
                    TasksViewModel(tasksRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                TasksScreen(vm) { connId, slug -> nav.navigate("taskDetail/$connId/$slug") }
            }
            composable(Section.SETTINGS.route) {
                val vm = viewModel { SettingsViewModel(connRepo, api, notificationsSetting) }
                SettingsScreen(vm)
            }
            composable(
                route = "specDetail/{connectionId}/{specId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("specId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val specId = entry.arguments?.getString("specId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the inbox.") }
                } else {
                    val title = remember(specId) { specId }
                    val vm = viewModel(key = "detail-$connectionId-$specId") {
                        SpecDetailViewModel(detailRepo, conn, specId)
                    }
                    SpecDetailScreen(vm, title = title, onBack = { nav.popBackStack() })
                }
            }
            composable(
                route = "taskDetail/{connectionId}/{slug}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("slug") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val slug = entry.arguments?.getString("slug").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to tasks.") }
                } else {
                    val vm = viewModel(key = "taskDetail-$connectionId-$slug") {
                        TaskDetailViewModel(tasksRepo, conn, slug)
                    }
                    TaskDetailScreen(vm, title = slug, onBack = { nav.popBackStack() })
                }
            }
            composable(
                route = "workspace/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to Home.") }
                } else {
                    val vm = viewModel(key = "workspace-$connectionId") {
                        WorkspaceViewModel(api, conn)
                    }
                    WorkspaceScreen(
                        vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onThread = { id -> nav.navigate("thread/$connectionId/$id") },
                        onSpec = { id -> nav.navigate("specDetail/$connectionId/$id") },
                        onTask = { slug -> nav.navigate("taskDetail/$connectionId/$slug") },
                        onNewConversation = { nav.navigate("newThread?connectionId=$connectionId") },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "farm/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed.") }
                } else {
                    val vm = viewModel(key = "farm-$connectionId") { FarmViewModel(api, conn) }
                    FarmScreen(
                        vm = vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onOpen = { item ->
                            when {
                                item.phase == "in_flight" -> nav.navigate("console/$connectionId/${item.id}")
                                item.phase == "review" -> nav.navigate("review/$connectionId/${item.id}")
                                item.phase == "done" -> nav.navigate("done/$connectionId/${item.id}")
                                // inbox / shaping / ready (spec-bearing) home to the spec cockpit; a spec-less inbox capture falls through to its thread
                                item.specId != null -> nav.navigate("specDetail/$connectionId/${item.specId}")
                                item.taskSlugs.isNotEmpty() -> nav.navigate("taskDetail/$connectionId/${item.taskSlugs.first()}")
                                item.threadIds.isNotEmpty() -> nav.navigate("thread/$connectionId/${item.threadIds.first()}")
                            }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "console/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else {
                    val vm = viewModel(key = "console-$connectionId-$itemId") {
                        ConsoleViewModel(api, conn, itemId)
                    }
                    ConsoleScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "review/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else {
                    val vm = viewModel(key = "review-$connectionId-$itemId") {
                        ReviewViewModel(api, conn, itemId)
                    }
                    ReviewScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "done/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else {
                    val vm = viewModel(key = "done-$connectionId-$itemId") {
                        DoneViewModel(api, conn, itemId)
                    }
                    DoneScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable(
                route = "item/{connectionId}/{itemId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                LaunchedEffect(connectionId, itemId) {
                    // This route is a pure redirect with no fallback UI of its own, so every
                    // dead-end pops back to where the user came from instead of stranding them on
                    // the transient spinner (reachable from the related-item card and from OS-level
                    // groundcontrol://item deep links).
                    if (conn == null) {
                        nav.popBackStack(); return@LaunchedEffect
                    }
                    val item = runCatching { api.getItem(conn, itemId) }.getOrNull()
                    if (item == null) {
                        nav.popBackStack(); return@LaunchedEffect
                    }
                    val dest = when {
                        item.phase == "in_flight" -> "console/$connectionId/$itemId"
                        item.phase == "review" -> "review/$connectionId/$itemId"
                        item.phase == "done" -> "done/$connectionId/$itemId"
                        // inbox / shaping / ready (spec-bearing) home to the spec cockpit; a spec-less
                        // inbox capture falls through to its task or thread — matches farm's onOpen.
                        item.specId != null -> "specDetail/$connectionId/${item.specId}"
                        item.taskSlugs.isNotEmpty() -> "taskDetail/$connectionId/${item.taskSlugs.first()}"
                        item.threadIds.isNotEmpty() -> "thread/$connectionId/${item.threadIds.first()}"
                        else -> null
                    }
                    if (dest == null) {
                        nav.popBackStack(); return@LaunchedEffect
                    }
                    nav.navigate(dest) { popUpTo("item/$connectionId/$itemId") { inclusive = true } }
                }
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }
            composable("capture") {
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                NewThreadScreen(
                    vm,
                    title = "Capture",
                    showSubject = false,
                    bodyLabel = "What's up?",
                    submitLabel = "Send",
                    onCreated = { connId, id ->
                        nav.navigate("thread/$connId/$id") {
                            popUpTo("capture") { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "newThread?connectionId={connectionId}",
                arguments = listOf(navArgument("connectionId") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                }),
            ) { entry ->
                val preselect = entry.arguments?.getString("connectionId")
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionsProvider = { runBlockingSnapshot(connRepo) })
                }
                NewThreadScreen(
                    vm,
                    initialConnectionId = preselect,
                    onCreated = { connId, id ->
                        nav.navigate("thread/$connId/$id") {
                            popUpTo("newThread?connectionId={connectionId}") { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "thread/{connectionId}/{threadId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("threadId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val threadId = entry.arguments?.getString("threadId").orEmpty()
                val conn = remember(connectionId) {
                    runBlockingSnapshot(connRepo).firstOrNull { it.id == connectionId }
                }
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to messages.") }
                } else {
                    val vm = viewModel(key = "thread-$connectionId-$threadId") {
                        ConversationViewModel(threadsRepo, conn, threadId)
                    }
                    ConversationScreen(
                        vm,
                        title = threadId,
                        onBack = { nav.popBackStack() },
                        onViewSpec = { specId -> nav.navigate("specDetail/$connectionId/$specId") },
                        onOpenEntity = { kind, id ->
                            when (kind) {
                                "item" -> nav.navigate("item/$connectionId/${Uri.encode(id)}")
                                "spec" -> nav.navigate("specDetail/$connectionId/${Uri.encode(id)}")
                                "task" -> nav.navigate("taskDetail/$connectionId/${Uri.encode(id)}")
                            }
                        },
                        onOpenWorkItem = { id -> nav.navigate("item/$connectionId/${Uri.encode(id)}") },
                    )
                }
            }
        }
    }

    if (pendingThread != null) {
        val pending by pendingThread.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            pending?.let { (connId, threadId) ->
                nav.navigate("thread/$connId/$threadId")
                pendingThread.value = null
            }
        }
    }
}

/** Bridge the suspend snapshot to the VM's sync provider on first refresh. */
private fun runBlockingSnapshot(repo: ConnectionsRepository): List<WorkspaceConnection> =
    runBlocking { repo.snapshot() }
