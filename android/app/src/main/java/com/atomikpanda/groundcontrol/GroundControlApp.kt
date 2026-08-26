package com.atomikpanda.groundcontrol

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atomikpanda.groundcontrol.data.ConnectionState
import com.atomikpanda.groundcontrol.data.ConnectionStateSource
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.appHttpClient
import com.atomikpanda.groundcontrol.data.DataStoreCoachMarkStore
import com.atomikpanda.groundcontrol.data.DataStoreNotificationsSetting
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.HomeFeedRepository
import com.atomikpanda.groundcontrol.data.QueueRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.SpecDetailRepository
import com.atomikpanda.groundcontrol.data.TasksRepository
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.findByConnectionId
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.notify.AndroidNeedsYouCanceller
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
import com.atomikpanda.groundcontrol.ui.projects.ProjectsScreen
import com.atomikpanda.groundcontrol.ui.projects.ProjectsViewModel
import com.atomikpanda.groundcontrol.ui.theme.LocalWorkspaceIdentityResolver
import com.atomikpanda.groundcontrol.ui.theme.WorkspaceIdentity
import com.atomikpanda.groundcontrol.ui.theme.resolveIdentity
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


internal fun newThreadRoute(connectionId: String?): String =
    if (connectionId != null) "newThread?connectionId=$connectionId" else "newThread"

internal fun connectionRoute(
    conn: WorkspaceConnection,
    destination: String,
    entityId: String,
): String = "$destination/${conn.id}/$entityId"

internal fun workItemRoute(conn: WorkspaceConnection, item: WorkItemSummary): String? = when {
    item.phase == "in_flight" -> connectionRoute(conn, "console", item.id)
    item.phase == "review" -> connectionRoute(conn, "review", item.id)
    item.phase == "done" -> connectionRoute(conn, "done", item.id)
    // Inbox / shaping / ready (spec-bearing) home to the spec cockpit; a spec-less
    // inbox capture falls through to its task or thread.
    item.specId != null -> connectionRoute(conn, "specDetail", item.specId)
    item.taskSlugs.isNotEmpty() -> connectionRoute(conn, "taskDetail", item.taskSlugs.first())
    item.threadIds.isNotEmpty() -> connectionRoute(conn, "thread", item.threadIds.first())
    else -> null
}

@Composable
internal fun ConnectionStateGate(
    state: ConnectionState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    ready: @Composable () -> Unit,
) {
    when (state) {
        ConnectionState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is ConnectionState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenSettings) { Text("Open Settings") }
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
        is ConnectionState.Ready -> ready()
    }
}

internal data class GroundControlDependencies(
    val connections: ConnectionsRepository,
    val hosts: HostsRepository,
    val connectionStateSource: ConnectionStateSource,
    val api: SpecApi,
    val home: HomeFeedRepository,
    val queue: QueueRepository,
    val detail: SpecDetailRepository,
    val tasks: TasksRepository,
    val threads: ThreadsRepository,
) {
    companion object {
        fun production(context: Context, source: ConnectionStateSource): GroundControlDependencies {
            val appContext = context.applicationContext
            val api = SpecApi(appHttpClient(appContext).client)
            return GroundControlDependencies(
                connections = ConnectionsRepository(appContext),
                hosts = HostsRepository(appContext),
                connectionStateSource = source,
                api = api,
                home = HomeFeedRepository(api),
                queue = QueueRepository(api),
                detail = SpecDetailRepository(api),
                tasks = TasksRepository(api),
                threads = ThreadsRepository(api),
            )
        }
    }
}

@Composable
fun GroundControlApp(
    context: Context,
    pendingThread: MutableStateFlow<Pair<String, String>?>? = null,
) {
    val source = remember(context.applicationContext) {
        (context.applicationContext as GroundControlApplication).connectionStateSource
    }
    val dependencies = remember(context.applicationContext, source) {
        GroundControlDependencies.production(context, source)
    }
    GroundControlContent(context, dependencies, pendingThread)
}


@Composable
internal fun GroundControlContent(
    context: Context,
    dependencies: GroundControlDependencies,
    pendingThread: MutableStateFlow<Pair<String, String>?>? = null,
) {
    val connectionStateSource = dependencies.connectionStateSource
    val nav = rememberNavController()
    val currentEntry by nav.currentBackStackEntryAsState()
    val connectionState by connectionStateSource.state.collectAsStateWithLifecycle()
    val connRepo = dependencies.connections
    val hostsRepo = dependencies.hosts
    val api = dependencies.api
    val homeRepo = dependencies.home
    val queueRepo = dependencies.queue
    val detailRepo = dependencies.detail
    val tasksRepo = dependencies.tasks
    val threadsRepo = dependencies.threads
    // Composition-scoped (cancelled on disposal) — not MainScope(), which would leak its
    // stateIn collector across Activity recreations.
    val appScope = rememberCoroutineScope()
    val notificationsSetting = remember { DataStoreNotificationsSetting(context.applicationContext, appScope) }
    val coachMark = remember { DataStoreCoachMarkStore(context.applicationContext, appScope) }
    // Home owns an Active-only messages snapshot, separate from the threads tab's selected
    // inbox/search state. Sharing one owner would let tab search reclassify Home's server feed.
    val homeMessagesVm: MessagesViewModel = viewModel(key = "homeMessages") {
        MessagesViewModel(threadsRepo, connectionStateSource.state)
    }
    val messagesVm: MessagesViewModel = viewModel(key = "inboxMessages") {
        MessagesViewModel(threadsRepo, connectionStateSource.state)
    }
    // Activity-scoped so relay links received on Home immediately trigger fleet
    // discovery; tying this observer to the Settings destination delays pairing.
    val settingsVm = viewModel {
        SettingsViewModel(connRepo, api, notificationsSetting, hostsRepo, connectionState = connectionStateSource)
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
        val readyConnections = (connectionState as? ConnectionState.Ready)?.connections.orEmpty()
        val connsForBadges = readyConnections
        // remember keyed on the connections so the resolver identity is stable across recompositions;
        // staticCompositionLocalOf invalidates every badge reader on a by-reference change, so a fresh
        // lambda each recomposition would needlessly re-render all badge sites (Greptile P2).
        val identityResolver: (String, String) -> WorkspaceIdentity = remember(connsForBadges) {
            { id, name -> resolveIdentity(connsForBadges, id, name) }
        }
        CompositionLocalProvider(LocalWorkspaceIdentityResolver provides identityResolver) {
        NavHost(nav, startDestination = Section.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Section.HOME.route) {
                val vm = viewModel {
                    HomeViewModel(
                        homeRepo,
                        connectionStateSource.state,
                        hosts = hostsRepo.hosts,
                    )
                }
                HomeScreen(
                    vm,
                    homeMessagesVm,
                    onApproval = { connId, specId -> nav.navigate("specDetail/$connId/$specId") },
                    onQuestion = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onBlocker = { connId, slug -> nav.navigate("taskDetail/$connId/$slug") },
                    onOpenThreads = {
                        (homeMessagesVm.state.value as? MessagesUiState.Content)?.let { home ->
                            messagesVm.selectWorkspace(home.selectedConnectionId)
                            messagesVm.selectStateFilter(home.stateFilter)
                        }
                        nav.navigate("threads")
                    },
                    onCapture = { nav.navigate("capture") },
                    onReviewInQueue = { nav.navigate(Section.QUEUE.route) { launchSingleTop = true } },
                    onRePair = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                )
            }
            composable(Section.QUEUE.route) {
                val vm = viewModel {
                    QueueViewModel(queueRepo, connectionStateSource.state, hosts = hostsRepo.hosts)
                }
                val uriHandler = LocalUriHandler.current
                QueueScreen(
                    vm,
                    coachMark = coachMark,
                    onOpenItem = { connId, itemId -> nav.navigate("item/$connId/$itemId") },
                    onOpenPr = { url -> uriHandler.openUri(url) },
                    onOpenTask = { connId, task -> nav.navigate("taskDetail/$connId/$task") },
                    onRePair = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                )
            }
            composable("threads") {
                MessagesScreen(
                    messagesVm,
                    onThreadClick = { connId, threadId -> nav.navigate("thread/$connId/$threadId") },
                    onNewThread = {
                        val connId = (messagesVm.state.value as? MessagesUiState.Content)?.selectedConnectionId
                        nav.navigate(newThreadRoute(connId))
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Section.TASKS.route) {
                val vm = viewModel {
                    TasksViewModel(tasksRepo, connectionStateSource.state)
                }
                TasksScreen(vm) { connId, slug -> nav.navigate("taskDetail/$connId/$slug") }
            }
            composable(Section.PROJECTS.route) {
                val vm = viewModel { ProjectsViewModel(connRepo, connectionStateSource.state, hostsRepo) }
                ProjectsScreen(vm, onOpenWorkspace = { connId -> nav.navigate("workspace/$connId") })
            }
            composable(Section.SETTINGS.route) {
                SettingsScreen(settingsVm)
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
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the inbox.") }
                } else {
                    val title = remember(specId) { specId }
                    val vm = viewModel {
                        SpecDetailViewModel(detailRepo, connectionId, specId, connectionStateSource.state)
                    }
                    SpecDetailScreen(vm, title = title, identity = LocalWorkspaceIdentityResolver.current(conn.id, conn.workspaceName.ifBlank { conn.baseUrl }), onBack = { nav.popBackStack() })
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
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to tasks.") }
                } else {
                    val vm = viewModel {
                        TaskDetailViewModel(tasksRepo, connectionId, slug, connectionStateSource.state)
                    }
                    TaskDetailScreen(vm, title = slug, onBack = { nav.popBackStack() })
                }
            }
            composable(
                route = "workspace/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to Home.") }
                } else {
                    val vm = viewModel {
                        WorkspaceViewModel(api, connectionId, connectionStateSource.state)
                    }
                    WorkspaceScreen(
                        vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onThread = { id -> nav.navigate(connectionRoute(conn, "thread", id)) },
                        onSpec = { id -> nav.navigate(connectionRoute(conn, "specDetail", id)) },
                        onTask = { slug -> nav.navigate(connectionRoute(conn, "taskDetail", slug)) },
                        onNewConversation = { nav.navigate(newThreadRoute(conn.id)) },
                        onBack = { nav.popBackStack() },
                        onRePair = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                    )
                }
            }
            composable(
                route = "farm/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed.") }
                } else {
                    val vm = viewModel { FarmViewModel(api, connectionId, connectionStateSource.state) }
                    FarmScreen(
                        vm = vm,
                        workspaceName = conn.workspaceName.ifBlank { conn.baseUrl },
                        onOpen = { item ->
                            workItemRoute(conn, item)?.let { nav.navigate(it) }
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
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else {
                    val vm = viewModel {
                        ConsoleViewModel(api, connectionId, itemId, connectionStateSource.state)
                    }
                    ConsoleScreen(
                        vm,
                        title = conn.workspaceName.ifBlank { conn.baseUrl },
                        identity = LocalWorkspaceIdentityResolver.current(conn.id, conn.workspaceName.ifBlank { conn.baseUrl }),
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
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else {
                    val vm = viewModel {
                        ReviewViewModel(api, connectionId, itemId, connectionStateSource.state)
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
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to the farm.") }
                } else {
                    val vm = viewModel {
                        DoneViewModel(api, connectionId, itemId, connectionStateSource.state)
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
                LaunchedEffect(connectionId, itemId) {
                    connectionStateSource.state.collectLatest { source ->
                        val ready = source as? ConnectionState.Ready ?: return@collectLatest
                        val connection = ready.connections.findByConnectionId(connectionId)
                        if (connection == null) {
                            nav.popBackStack()
                            return@collectLatest
                        }
                        val item = runCatching { api.getItem(connection, itemId) }.getOrNull()
                        val current = (connectionStateSource.state.value as? ConnectionState.Ready)
                            ?.connections
                            ?.findByConnectionId(connectionId)
                        if (current != connection || item == null) {
                            if (item == null && current == connection) nav.popBackStack()
                            return@collectLatest
                        }
                        val dest = workItemRoute(connection, item)
                        if (dest == null) {
                            nav.popBackStack()
                            return@collectLatest
                        }
                        nav.navigate(dest) {
                            popUpTo("item/$connectionId/$itemId") { inclusive = true }
                        }
                    }
                }
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }
            composable("capture") {
                val vm = viewModel {
                    NewThreadViewModel(threadsRepo, connectionStateSource.state)
                }
                NewThreadScreen(
                    vm,
                    title = "Capture",
                    showSubject = false,
                    bodyLabel = "What's up?",
                    submitLabel = "Send",
                    showKindPicker = true,
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
                    NewThreadViewModel(threadsRepo, connectionStateSource.state)
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
                val conn = readyConnections.findByConnectionId(connectionId)
                if (conn == null) {
                    Box(Modifier.fillMaxSize()) { Text("Connection removed. Go back to messages.") }
                } else {
                    val vm = viewModel {
                        ConversationViewModel(
                            threadsRepo, connectionId, threadId, connectionStateSource.state,
                            canceller = AndroidNeedsYouCanceller(context.applicationContext),
                        )
                    }
                    ConversationScreen(
                        vm,
                        title = threadId,
                        identity = LocalWorkspaceIdentityResolver.current(conn.id, conn.workspaceName.ifBlank { conn.baseUrl }),
                        onBack = { nav.popBackStack() },
                        onViewSpec = { specId -> nav.navigate(connectionRoute(conn, "specDetail", specId)) },
                        onOpenEntity = { kind, id ->
                            when (kind) {
                                "item" -> nav.navigate(connectionRoute(conn, "item", Uri.encode(id)))
                                "spec" -> nav.navigate(connectionRoute(conn, "specDetail", Uri.encode(id)))
                                "task" -> nav.navigate(connectionRoute(conn, "taskDetail", Uri.encode(id)))
                            }
                        },
                        onOpenWorkItem = { id -> nav.navigate(connectionRoute(conn, "item", Uri.encode(id))) },
                    )
                }
            }
        }
        }
        if (currentEntry?.destination?.route != Section.SETTINGS.route && connectionState !is ConnectionState.Ready) {
            ConnectionStateGate(
                state = connectionState,
                onRetry = connectionStateSource::retry,
                onOpenSettings = { nav.navigate(Section.SETTINGS.route) { launchSingleTop = true } },
                ready = {},
            )
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
