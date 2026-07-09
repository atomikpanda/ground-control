package com.atomikpanda.groundcontrol.ui.messages

import com.atomikpanda.groundcontrol.notify.DeepLinkResolver
import java.net.URI

/**
 * Parses `groundcontrol://<kind>?id=<id>` links embedded in agent message markdown so
 * [MessageMarkdown]'s custom `UriHandler` can navigate in-app on tap instead of falling through
 * to the OS/browser handler used for external links. Inline taps are always scoped to the
 * conversation's current connection, so — unlike
 * [com.atomikpanda.groundcontrol.notify.DeepLinkResolver], which resolves OS-level deep links
 * across workspaces — there's no `workspace` param to resolve here, and no connection lookup.
 */
object EntityLink {
    /** `thread` is deliberately excluded: inline thread links are handled elsewhere (e.g. the
     *  "View spec ->" affordance), not by this tap-to-navigate path. */
    private val supportedKinds = setOf("item", "spec", "task")

    /** Returns (kind, id) for a recognized entity link, or null if [uri] isn't one. */
    fun parse(uri: String): Pair<String, String>? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (parsed.scheme != "groundcontrol" || parsed.host !in supportedKinds) return null
        val id = DeepLinkResolver.parseQuery(parsed.rawQuery)["id"]?.takeIf { it.isNotBlank() } ?: return null
        return parsed.host to id
    }
}
