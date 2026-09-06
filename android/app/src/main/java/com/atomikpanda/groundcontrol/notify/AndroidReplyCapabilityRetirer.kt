package com.atomikpanda.groundcontrol.notify

import android.content.Context

/** Retires an actionable reply capability after an authoritative foreground acknowledgement. */
internal class AndroidReplyCapabilityRetirer(private val context: Context) {
    suspend fun retire(connectionId: String, threadId: String, sourceVersion: String): Boolean {
        ReplyStartupGate.awaitReset()
        val appContext = context.applicationContext
        return NotificationRenderCoordinator(
            NotifiedDatabase.get(appContext),
            AndroidNotifier(appContext),
            AndroidNeedsYouCanceller(appContext)::cancel,
        ).retire(connectionId, threadId, sourceVersion)
    }
}
