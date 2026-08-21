package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** ONE DataStore over the `ground_control` file, shared with [HostsRepository]:
 *  a second `preferencesDataStore(name = "ground_control")` delegate would open
 *  the same file twice and fail at runtime. */
internal val Context.dataStore by preferencesDataStore(name = "ground_control")
internal val CONNECTIONS = stringPreferencesKey("connections")

class ConnectionsRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)
    val connections: Flow<List<WorkspaceConnection>> =
        dataStore.data
            .map { ConnectionsCodec.decode(it[CONNECTIONS] ?: "") }
            .distinctUntilChanged()

    suspend fun snapshot(): List<WorkspaceConnection> =
        ConnectionsCodec.decode(dataStore.data.first()[CONNECTIONS] ?: "")

    /** All writes are read-modify-write inside ONE edit transform — DataStore
     *  serializes transforms, so concurrent mutations (e.g. two quick "Add"
     *  taps) can't snapshot the same list and lose each other's write. */
    private suspend fun mutate(transform: (List<WorkspaceConnection>) -> List<WorkspaceConnection>) {
        dataStore.edit {
            val current = ConnectionsCodec.decode(it[CONNECTIONS] ?: "")
            val updated = transform(current)
            it.advanceRouteOwnershipGeneration(updated)
            it[CONNECTIONS] = ConnectionsCodec.encode(updated)
        }
    }

    /** Explicit pair/re-pair input is authoritative: a blank token must not
     * resurrect the prior row's retained direct credential. */
    suspend fun upsert(conn: WorkspaceConnection) =
        mutate { upsertConnection(it, conn, preservePriorDirectToken = false) }


    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }


    suspend fun setIdentity(id: String, colorOverride: String?, glyphOverride: String?) =
        mutate { applyIdentityOverride(it, id, colorOverride, glyphOverride) }
}
