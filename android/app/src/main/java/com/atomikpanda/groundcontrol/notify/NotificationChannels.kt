package com.atomikpanda.groundcontrol.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val NEEDS_YOU = "agent_needs_you"
    const val WATCHING = "watching_for_messages"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(NotificationChannel(NEEDS_YOU, "Agent needs you", NotificationManager.IMPORTANCE_HIGH))
        mgr.createNotificationChannel(NotificationChannel(WATCHING, "Watching for messages", NotificationManager.IMPORTANCE_LOW))
    }
}
