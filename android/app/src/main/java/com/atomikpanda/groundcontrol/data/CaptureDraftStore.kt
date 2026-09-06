package com.atomikpanda.groundcontrol.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A capture draft belongs to the route context that opened the capture composer. A null context is
 * the all-workspaces entry point, not a wildcard for workspace-scoped drafts.
 */
@Serializable
data class CaptureDraft(
    val subject: String,
    val text: String,
    val kind: String,
    val selectedConnectionId: String?,
    val brainstormIdempotencyKey: String?,
    val attemptedBrainstorm: CaptureDraftPayload?,
    val revision: Long,
    /** A revision tombstone prevents a delayed older save from resurrecting a discarded draft. */
    val isDiscarded: Boolean = false,
)

/** The exact request payload guarded by a brainstorm idempotency key. */
@Serializable
data class CaptureDraftPayload(
    val connectionId: String,
    val subject: String?,
    val text: String,
)

/** App-private, context-scoped storage for unfinished capture-composer input. */
interface CaptureDraftStore {
    /** Null means no restorable draft (including a deliberate discard tombstone). */
    suspend fun load(contextId: String?): CaptureDraft?
    /** Includes discard tombstones so the next writer always advances their revision. */
    suspend fun latestRevision(contextId: String?): Long
    suspend fun save(contextId: String?, draft: CaptureDraft)

    /** Places a tombstone for this revision unless a newer replacement already exists. */
    suspend fun clear(contextId: String?, expectedRevision: Long)
}

private val CAPTURE_DRAFTS = stringPreferencesKey("capture_drafts")
private const val ALL_WORKSPACES_CAPTURE_CONTEXT = "\u0000all-workspaces"
private val captureDraftJson = Json { ignoreUnknownKeys = true }
private val captureDraftsSerializer = MapSerializer(String.serializer(), CaptureDraft.serializer())

/**
 * Reuses [dataStore], the app's single pairing-state DataStore. Revisions make delayed persistence
 * from an older composer unable to replace newer input or clear a newer draft.
 */
class DataStoreCaptureDraftStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : CaptureDraftStore {
    constructor(context: Context) : this(context.dataStore)

    override suspend fun load(contextId: String?): CaptureDraft? =
        decode(dataStore.data.first()[CAPTURE_DRAFTS])[contextId.storageKey()]?.takeUnless { it.isDiscarded }

    override suspend fun latestRevision(contextId: String?): Long =
        decode(dataStore.data.first()[CAPTURE_DRAFTS])[contextId.storageKey()]?.revision ?: 0L

    override suspend fun save(contextId: String?, draft: CaptureDraft) {
        val key = contextId.storageKey()
        dataStore.edit { preferences ->
            val drafts = decode(preferences[CAPTURE_DRAFTS])
            val current = drafts[key]
            if (current == null || current.revision < draft.revision || current == draft) {
                preferences[CAPTURE_DRAFTS] = encode(drafts + (key to draft))
            }
        }
    }

    override suspend fun clear(contextId: String?, expectedRevision: Long) {
        val key = contextId.storageKey()
        dataStore.edit { preferences ->
            val drafts = decode(preferences[CAPTURE_DRAFTS])
            val current = drafts[key]
            if (current == null || current.revision <= expectedRevision) {
                preferences[CAPTURE_DRAFTS] = encode(
                    drafts + (key to CaptureDraft(
                        subject = "",
                        text = "",
                        kind = "QUICK_NOTE",
                        selectedConnectionId = null,
                        brainstormIdempotencyKey = null,
                        attemptedBrainstorm = null,
                        revision = expectedRevision,
                        isDiscarded = true,
                    )),
                )
            }
        }
    }

    private fun decode(raw: String?): Map<String, CaptureDraft> =
        raw?.let { runCatching { captureDraftJson.decodeFromString(captureDraftsSerializer, it) }.getOrDefault(emptyMap()) }
            ?: emptyMap()

    private fun encode(drafts: Map<String, CaptureDraft>): String =
        captureDraftJson.encodeToString(captureDraftsSerializer, drafts)
}

private fun String?.storageKey(): String = this ?: ALL_WORKSPACES_CAPTURE_CONTEXT
