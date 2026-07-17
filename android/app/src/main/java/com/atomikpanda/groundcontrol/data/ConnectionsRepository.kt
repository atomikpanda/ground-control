package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ground_control")
private val CONNECTIONS = stringPreferencesKey("connections")

class ConnectionsRepository(private val context: Context) {
    val connections: Flow<List<WorkspaceConnection>> =
        context.dataStore.data.map { ConnectionsCodec.decode(it[CONNECTIONS] ?: "") }

    suspend fun snapshot(): List<WorkspaceConnection> =
        ConnectionsCodec.decode(context.dataStore.data.first()[CONNECTIONS] ?: "")

    suspend fun save(list: List<WorkspaceConnection>) {
        context.dataStore.edit { it[CONNECTIONS] = ConnectionsCodec.encode(list) }
    }

    suspend fun upsert(conn: WorkspaceConnection) =
        save(upsertConnection(snapshot(), conn))

    suspend fun remove(id: String) = save(snapshot().filterNot { it.id == id })

    suspend fun setIdentity(id: String, colorOverride: String?, glyphOverride: String?) =
        save(applyIdentityOverride(snapshot(), id, colorOverride, glyphOverride))
}
