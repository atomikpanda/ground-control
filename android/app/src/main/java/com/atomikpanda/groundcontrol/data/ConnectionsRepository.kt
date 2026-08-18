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

    /** All writes are read-modify-write inside ONE edit transform — DataStore
     *  serializes transforms, so concurrent mutations (e.g. two quick "Add"
     *  taps) can't snapshot the same list and lose each other's write. */
    private suspend fun mutate(transform: (List<WorkspaceConnection>) -> List<WorkspaceConnection>) {
        context.dataStore.edit {
            it[CONNECTIONS] = ConnectionsCodec.encode(transform(ConnectionsCodec.decode(it[CONNECTIONS] ?: "")))
        }
    }

    suspend fun upsert(conn: WorkspaceConnection) = mutate { upsertConnection(it, conn) }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setIdentity(id: String, colorOverride: String?, glyphOverride: String?) =
        mutate { applyIdentityOverride(it, id, colorOverride, glyphOverride) }
}
