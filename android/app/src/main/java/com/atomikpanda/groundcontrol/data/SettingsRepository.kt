package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

interface NotificationsSetting {
    val enabled: StateFlow<Boolean>
    suspend fun set(value: Boolean)
}

// Single shared DataStore delegate for app settings. A second delegate for the same file name
// anywhere in the process crashes ("multiple DataStores active for the same file"), so
// BootReceiver imports these rather than re-declaring them.
internal val Context.settingsStore by preferencesDataStore(name = "ground_control_settings")
internal val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")

class DataStoreNotificationsSetting(
    private val context: Context,
    scope: CoroutineScope,
) : NotificationsSetting {
    override val enabled: StateFlow<Boolean> =
        context.settingsStore.data.map { it[NOTIFICATIONS_ENABLED] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun set(value: Boolean) {
        context.settingsStore.edit { it[NOTIFICATIONS_ENABLED] = value }
    }
}
