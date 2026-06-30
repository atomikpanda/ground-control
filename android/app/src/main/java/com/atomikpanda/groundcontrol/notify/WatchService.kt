package com.atomikpanda.groundcontrol.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.SpecApi
import com.atomikpanda.groundcontrol.data.ThreadsRepository
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.defaultHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private lateinit var reconciler: NeedsYouReconciler
    private lateinit var repo: ThreadsRepository
    private lateinit var connections: ConnectionsRepository

    override fun onCreate() {
        super.onCreate()
        repo = ThreadsRepository(SpecApi(defaultHttpClient()))
        connections = ConnectionsRepository(applicationContext)
        reconciler = NeedsYouReconciler(
            RoomNotifiedStore(NotifiedDatabase.get(applicationContext).notifiedDao()),
            AndroidNotifier(applicationContext),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watching = NotificationCompat.Builder(this, NotificationChannels.WATCHING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Watching for messages")
            .setOngoing(true)
            .build()
        // 3-arg (typed) startForeground so API 34 never throws MissingForegroundServiceTypeException.
        ServiceCompat.startForeground(
            this, WATCH_NOTIFICATION_ID, watching,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        // Keep exactly one watch loop: START_STICKY redelivery + an explicit start (e.g.
        // BootReceiver) can both call onStartCommand; without this guard each appends another
        // collectLatest loop, doubling the long-poll traffic per connection.
        if (watchJob?.isActive != true) {
            watchJob = scope.launch {
                // Re-spread watchers whenever the connection set changes.
                connections.connections.collectLatest { conns ->
                    coroutineScope {
                        conns.forEach { conn -> launch { watchOne(conn) } }
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun CoroutineScope.watchOne(conn: WorkspaceConnection) {
        var cursor = Instant.now().toString()
        var backoffMs = 1000L
        while (isActive) {
            val resp = runCatching { repo.waitForChange(conn, cursor, 25) }.getOrNull()
            if (resp == null) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                continue
            }
            backoffMs = 1000L
            reconciler.reconcile(conn, resp.threads)
            if (resp.cursor.isNotEmpty()) cursor = resp.cursor
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val WATCH_NOTIFICATION_ID = 42
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
