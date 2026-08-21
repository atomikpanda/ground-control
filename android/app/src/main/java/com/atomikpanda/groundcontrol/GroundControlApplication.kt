package com.atomikpanda.groundcontrol

import android.app.Application
import android.app.NotificationManager
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.notify.AndroidNeedsYouCanceller
import com.atomikpanda.groundcontrol.notify.NotificationChannels
import com.atomikpanda.groundcontrol.notify.NotifiedDatabase
import com.atomikpanda.groundcontrol.notify.ReplyOutbox
import com.atomikpanda.groundcontrol.notify.NotificationRenderCoordinator
import com.atomikpanda.groundcontrol.notify.AndroidNotifier
import com.atomikpanda.groundcontrol.notify.ReplyMigrationResetter
import com.atomikpanda.groundcontrol.notify.WorkManagerReplyScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GroundControlApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        scope.launch {
            val database = NotifiedDatabase.get(this@GroundControlApplication)
            ReplyMigrationResetter(database) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.activeNotifications
                    .filter { it.notification.channelId == NotificationChannels.NEEDS_YOU }
                    .forEach { manager.cancel(it.id) }
            }.resetIfRequired()
            val renderer = NotificationRenderCoordinator(
                database,
                AndroidNotifier(this@GroundControlApplication),
                AndroidNeedsYouCanceller(this@GroundControlApplication)::cancel,
            )
            ReplyOutbox(
                database,
                WorkManagerReplyScheduler(this@GroundControlApplication),
                { ConnectionsRepository(this@GroundControlApplication).snapshot() },
                { submission -> AndroidNeedsYouCanceller(this@GroundControlApplication).cancel(submission.connectionId, submission.threadId) },
            ).reconcileEligible()
            renderer.reconcilePending()
        }
    }
}
