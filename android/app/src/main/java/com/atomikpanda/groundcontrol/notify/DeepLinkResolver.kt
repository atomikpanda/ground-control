package com.atomikpanda.groundcontrol.notify

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import java.net.URI
import java.net.URLDecoder

sealed interface DeepLinkOutcome {
    data class OpenThread(val connectionId: String, val threadId: String) : DeepLinkOutcome
    data class AddConnection(val workspaceKey: String) : DeepLinkOutcome
    data object Ignore : DeepLinkOutcome
}

object DeepLinkResolver {
    fun resolve(raw: String, connections: List<WorkspaceConnection>): DeepLinkOutcome {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return DeepLinkOutcome.Ignore
        if (uri.scheme != "groundcontrol" || uri.host != "thread") return DeepLinkOutcome.Ignore
        val params = parseQuery(uri.rawQuery)
        val threadId = params["id"]?.takeIf { it.isNotBlank() } ?: return DeepLinkOutcome.Ignore
        val key = params["workspace"]?.takeIf { it.isNotBlank() } ?: return DeepLinkOutcome.Ignore

        val normKey = normalizedBaseUrl(key)
        val match = connections.firstOrNull { normKey != null && normalizedBaseUrl(it.baseUrl) == normKey }
            ?: connections.firstOrNull { it.workspaceName.isNotBlank() && it.workspaceName == key }
        return if (match != null) DeepLinkOutcome.OpenThread(match.id, threadId)
        else DeepLinkOutcome.AddConnection(key)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        (rawQuery ?: "").split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
}
