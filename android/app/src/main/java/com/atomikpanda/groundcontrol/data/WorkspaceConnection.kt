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
 * Pure upsert: replace any existing entry matching [conn] by [id] or [baseUrl], else append.
 * Re-pairing carries forward a prior entry's colorOverride/glyphOverride when [conn] omits them,
 * so re-pairing a workspace never silently resets a customized identity (ac5). An explicit
 * override on [conn] still wins.
 */
fun upsertConnection(
    existing: List<WorkspaceConnection>,
    conn: WorkspaceConnection,
): List<WorkspaceConnection> {
    val prior = existing.firstOrNull { it.id == conn.id || it.baseUrl == conn.baseUrl }
    val merged = conn.copy(
        colorOverride = conn.colorOverride ?: prior?.colorOverride,
        glyphOverride = conn.glyphOverride ?: prior?.glyphOverride,
    )
    return existing.filterNot { it.id == conn.id || it.baseUrl == conn.baseUrl } + merged
}

/** Pure override editor: replace the identity override on the entry with [id] (null clears it,
 *  resetting that field to the auto-derived value). Used by the Projects tab edit affordance. */
fun applyIdentityOverride(
    list: List<WorkspaceConnection>,
    id: String,
    colorOverride: String?,
    glyphOverride: String?,
): List<WorkspaceConnection> =
    list.map { if (it.id == id) it.copy(colorOverride = colorOverride, glyphOverride = glyphOverride) else it }

/** Trim, strip a trailing slash, and require an http(s) scheme. Returns null if invalid. */
fun normalizedBaseUrl(input: String): String? {
    val t = input.trim().trimEnd('/')
    if (!t.startsWith("http://") && !t.startsWith("https://")) return null
    if (t.substringAfter("://").isBlank()) return null
    return t
}
