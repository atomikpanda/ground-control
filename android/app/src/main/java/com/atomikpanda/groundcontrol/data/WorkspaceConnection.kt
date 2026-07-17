package com.atomikpanda.groundcontrol.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceConnection(
    val id: String,
    val baseUrl: String,
    val token: String? = null,
    val workspaceName: String = "",
    /** Operator override for the identity badge color, "#AARRGGBB"; null = auto-derived. */
    val colorOverride: String? = null,
    /** Operator override for the identity badge glyph; null = auto (name's first letter). */
    val glyphOverride: String? = null,
)

/** Pure (de)serialization of the connection list stored in DataStore. */
object ConnectionsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WorkspaceConnection.serializer())

    fun encode(list: List<WorkspaceConnection>): String = json.encodeToString(serializer, list)

    fun decode(raw: String): List<WorkspaceConnection> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

/**
 * Pure upsert helper: removes any existing entry whose [id] or [baseUrl] matches [conn],
 * then appends [conn]. Re-pairing the same URL replaces the old entry (new token/name win).
 */
fun upsertConnection(
    existing: List<WorkspaceConnection>,
    conn: WorkspaceConnection,
): List<WorkspaceConnection> =
    existing.filterNot { it.id == conn.id || it.baseUrl == conn.baseUrl } + conn

/** Trim, strip a trailing slash, and require an http(s) scheme. Returns null if invalid. */
fun normalizedBaseUrl(input: String): String? {
    val t = input.trim().trimEnd('/')
    if (!t.startsWith("http://") && !t.startsWith("https://")) return null
    if (t.substringAfter("://").isBlank()) return null
    return t
}
