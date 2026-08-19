package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** ONE DataStore over the `ground_control` file, shared with [HostsRepository]:
 *  a second `preferencesDataStore(name = "ground_control")` delegate would open
 *  the same file twice and fail at runtime. */
internal val Context.dataStore by preferencesDataStore(name = "ground_control")
internal val CONNECTIONS = stringPreferencesKey("connections")

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

    /** Upsert one explicitly selected discovery, adopting only identities the
     * selected row's own host verified before this serialized write. */
    suspend fun upsertDiscovered(
        conn: WorkspaceConnection,
        identities: List<VerifiedIdentity>,
    ) = mutate { adoptManualConnections(it, listOf(conn), identities) }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Replace one host's authoritative workspace set, adopting verified manual
     * rows inside the same serialized transform. */
    suspend fun replaceHost(
        hostId: String,
        discovered: List<WorkspaceConnection>,
        identities: List<VerifiedIdentity>,
    ) = mutate { replaceHostConnections(it, hostId, discovered, identities) }

    suspend fun setIdentity(id: String, colorOverride: String?, glyphOverride: String?) =
        mutate { applyIdentityOverride(it, id, colorOverride, glyphOverride) }
}
