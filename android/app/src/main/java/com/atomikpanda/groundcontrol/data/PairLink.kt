package com.atomikpanda.groundcontrol.data

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

object PairLink {
    /**
     * Parse a groundcontrol://add?url=&token=&workspace= deep link into a
     * [WorkspaceConnection], or null if the scheme/host is wrong or `url` is missing.
     *
     * Uses [java.net.URI] (pure JVM) instead of android.net.Uri so this runs in
     * plain JUnit tests without Robolectric.
     *
     * The producer (mship's build_pair_link) percent-encodes values with quote():
     *   space→%20, +→%2B, /→%2F, =→%3D (never emits a literal +).
     * We decode with URLDecoder.decode(..., "UTF-8") which is safe because there
     * are no literal + signs in the encoded value that would be mis-decoded as space.
     */
    fun parse(raw: String): WorkspaceConnection? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "groundcontrol" || uri.host != "add") return null

        val params = parseQuery(uri.rawQuery ?: return null)

        val url = params["url"]?.takeIf { it.isNotBlank() } ?: return null
        val token = params["token"]?.takeIf { it.isNotBlank() }
        val workspace = params["workspace"].orEmpty()

        return WorkspaceConnection(
            id = UUID.randomUUID().toString(),
            baseUrl = url,
            token = token,
            workspaceName = workspace,
        )
    }

    /**
     * Parse a raw query string (the part after `?`) into a map of key→decoded value.
     * Splits on `&`, then splits each pair on the FIRST `=` only, so values containing
     * `=` (e.g. base64 or tokens) round-trip correctly.
     */
    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }
}
