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
import com.atomikpanda.groundcontrol.notify.ReplyStartupGate
import com.atomikpanda.groundcontrol.notify.withReplyStartupGate
import com.atomikpanda.groundcontrol.notify.WorkManagerReplyScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REPLY_RESET_RETRY_DELAY_MILLIS = 1_000L

class GroundControlApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        // Close synchronously before the reset coroutine is launched: watchers that start now
        // must await this exact generation rather than racing mutex acquisition.
        ReplyStartupGate.beginReset()
        scope.launch {
            lateinit var database: NotifiedDatabase
            while (true) {
                try {
                    database = withReplyStartupGate {
                        NotifiedDatabase.get(this@GroundControlApplication).also {
                            ReplyMigrationResetter(it) {
                                val manager = getSystemService(NotificationManager::class.java)
                                manager.activeNotifications
                                    .filter { notification -> notification.notification.channelId == NotificationChannels.NEEDS_YOU }
                                    .forEach { notification -> manager.cancel(notification.id) }
                            }.resetIfRequired()
                        }
                    }
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    delay(REPLY_RESET_RETRY_DELAY_MILLIS)
                }
            }
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
