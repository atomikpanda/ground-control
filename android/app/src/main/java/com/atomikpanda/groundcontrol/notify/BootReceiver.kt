package com.atomikpanda.groundcontrol.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atomikpanda.groundcontrol.data.NOTIFICATIONS_ENABLED
import com.atomikpanda.groundcontrol.data.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Reuse the single settings DataStore delegate from SettingsRepository — a second delegate
        // for the same file would crash. runBlocking is a brief read on the receiver thread, well
        // under the ANR window.
        val enabled = runBlocking { context.settingsStore.data.first()[NOTIFICATIONS_ENABLED] ?: false }
        if (enabled) WatchController.enable(context.applicationContext)
    }
}
