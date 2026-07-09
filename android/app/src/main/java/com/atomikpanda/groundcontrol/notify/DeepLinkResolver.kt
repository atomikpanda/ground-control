package com.atomikpanda.groundcontrol.notify

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.normalizedBaseUrl
import java.net.URI
import java.net.URLDecoder

sealed interface DeepLinkOutcome {
    data class OpenThread(val connectionId: String, val threadId: String) : DeepLinkOutcome
    data class OpenItem(val connectionId: String, val itemId: String) : DeepLinkOutcome
    data class OpenSpec(val connectionId: String, val specId: String) : DeepLinkOutcome
    data class OpenTask(val connectionId: String, val slug: String) : DeepLinkOutcome
    data class AddConnection(val workspaceKey: String) : DeepLinkOutcome
    data object Ignore : DeepLinkOutcome
}

object DeepLinkResolver {
    private val entityHosts = setOf("thread", "item", "spec", "task")

    fun resolve(raw: String, connections: List<WorkspaceConnection>): DeepLinkOutcome {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return DeepLinkOutcome.Ignore
        if (uri.scheme != "groundcontrol" || uri.host !in entityHosts) return DeepLinkOutcome.Ignore
        val params = parseQuery(uri.rawQuery)
        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return DeepLinkOutcome.Ignore
        val key = params["workspace"]?.takeIf { it.isNotBlank() } ?: return DeepLinkOutcome.Ignore

        val normKey = normalizedBaseUrl(key)
        val match = connections.firstOrNull { normKey != null && normalizedBaseUrl(it.baseUrl) == normKey }
            ?: connections.firstOrNull { it.workspaceName.isNotBlank() && it.workspaceName == key }
        if (match == null) return DeepLinkOutcome.AddConnection(key)
        return when (uri.host) {
            "thread" -> DeepLinkOutcome.OpenThread(match.id, id)
            "item" -> DeepLinkOutcome.OpenItem(match.id, id)
            "spec" -> DeepLinkOutcome.OpenSpec(match.id, id)
            "task" -> DeepLinkOutcome.OpenTask(match.id, id)
            else -> DeepLinkOutcome.Ignore
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        (rawQuery ?: "").split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
}
