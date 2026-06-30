package com.atomikpanda.groundcontrol.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.bootSettingsStore by preferencesDataStore(name = "ground_control_settings")

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val key = booleanPreferencesKey("notifications_enabled")
        val enabled = runBlocking { context.bootSettingsStore.data.first()[key] ?: false }
        if (enabled) WatchController.enable(context.applicationContext)
    }
}
