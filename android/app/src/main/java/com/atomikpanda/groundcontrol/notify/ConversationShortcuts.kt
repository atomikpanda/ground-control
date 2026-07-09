package com.atomikpanda.groundcontrol.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.atomikpanda.groundcontrol.MainActivity

/**
 * Manages one long-lived dynamic conversation shortcut per notified thread so the notification
 * lands in the Android conversation surface (API 30+; degrades gracefully below). The shortcut id
 * doubles as the notification's LocusId.
 *
 * Dynamic shortcuts have a per-activity platform cap; we prune the oldest conversation shortcuts
 * before pushing a new one so we never exceed it. The shortcut for the thread being notified is
 * always (re)pushed, so an active conversation keeps its shortcut even under pruning.
 */
object ConversationShortcuts {
    private const val PREFIX = "thread_"

    /** Required so the shortcut/notification is eligible for the Conversations shade/bubbles. */
    private const val SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation"

    /** Fallback cap when the platform doesn't report one. Docs guarantee at least 5. */
    private const val DEFAULT_MAX = 4

    fun shortcutId(connId: String, threadId: String): String = "$PREFIX${connId}_$threadId"

    /**
     * Push (or update) the conversation shortcut for a thread and return its id for
     * `setShortcutId`/`setLocusId`. Best-effort: any platform failure is swallowed and returns the
     * id anyway so the notification still renders (just without conversation placement).
     */
    fun push(
        context: Context,
        connId: String,
        threadId: String,
        deepLink: String,
        agent: Person,
        label: String,
    ): String {
        val id = shortcutId(connId, threadId)
        val cap = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }
            .getOrDefault(0)
            .let { if (it > 0) minOf(it, DEFAULT_MAX) else DEFAULT_MAX }
        prune(context, keep = id, cap = cap)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(deepLink)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label.ifBlank { "Conversation" })
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setPerson(agent)
            .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION))
            .setIntent(intent)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return id
    }

    /** Drop the oldest surplus conversation shortcuts so pushing [keep] won't exceed [cap]. */
    private fun prune(context: Context, keep: String, cap: Int) {
        val existing = runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }
            .getOrDefault(emptyList())
        val toRemove = idsToPrune(existing.map { it.id }, PREFIX, keep, cap)
        if (toRemove.isNotEmpty()) {
            runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, toRemove) }
        }
    }

    /**
     * Pure sizing logic for [prune]: the platform's dynamic-shortcut cap applies to ALL dynamic
     * shortcuts (not just ones we own that start with [prefix]), so overage must be computed
     * against [existingIds] in full. We only ever remove shortcuts we own (the [prefix]'d ones,
     * excluding [keep]) — pruning surplus conversation shortcuts first/only — and never count
     * [keep] twice if it's already dynamic (re-pushing it doesn't grow the total).
     */
    internal fun idsToPrune(existingIds: List<String>, prefix: String, keep: String, cap: Int): List<String> {
        val willAdd = if (keep in existingIds) 0 else 1
        val overBy = (existingIds.size + willAdd) - cap
        if (overBy <= 0) return emptyList()
        val conversationCandidates = existingIds.filter { it.startsWith(prefix) && it != keep }
        return conversationCandidates.take(overBy)
    }
}
