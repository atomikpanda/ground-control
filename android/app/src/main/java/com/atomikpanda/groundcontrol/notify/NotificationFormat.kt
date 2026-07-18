package com.atomikpanda.groundcontrol.notify

import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pure (Android-free) formatting helpers for the notification render path. Kept out of
 * [AndroidNotifier] so they're straightforward JVM unit tests: timestamp parsing, decision →
 * option-action selection, RemoteInput → post-body mapping, markdown normalization, and the
 * active-decision / recent-message slicing.
 */

/** One option rendered as a notification Action button (posts [text] verbatim). */
data class NotificationOption(val index: Int, val text: String)

/**
 * Parse an ISO-8601 instant to epoch millis. Accepts both `...Z` (ISO_INSTANT) and offset forms
 * (`...+00:00`). Returns null for blank/unparseable values so callers can fall back to a
 * now-relative timestamp.
 */
fun parseTimestampMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Map each message's `created_at` to epoch millis, preserving order. Unparseable/absent timestamps
 * fall back to `now - (secondsFromEnd)` so a message with no usable timestamp still sorts after the
 * ones before it and never lands in the future.
 */
fun messageTimestamps(createdAts: List<String?>, now: Long): List<Long> =
    createdAts.mapIndexed { i, iso ->
        parseTimestampMillis(iso) ?: (now - (createdAts.size - 1 - i) * 1000L)
    }

/** The most recent [limit] messages (the tail), used as MessagingStyle context. */
fun recentMessages(messages: List<Message>, limit: Int = 6): List<Message> =
    if (messages.size <= limit) messages else messages.subList(messages.size - limit, messages.size)

/**
 * The active (still-unanswered) decision: the most recent `decision` message that has no human
 * reply after it. Mirrors the in-app rule (ConversationScreen / ConsoleViewModel) so the
 * notification offers option buttons only when the app itself would.
 */
fun activeDecision(messages: List<Message>): Decision? {
    val lastHuman = messages.indexOfLast { it.role == "human" }
    return messages.drop(lastHuman + 1).lastOrNull { it.kind == "decision" }?.decision
}

/**
 * The option-action buttons to render for a decision, recommended option first, capped to [cap].
 * Multi-select decisions render NO buttons (reply-only); so does a null/empty decision. Posting an
 * option's raw text is exactly an in-app option tap (bypasses the allow_free_text gate).
 */
fun decisionActionOptions(decision: Decision?, cap: Int): List<NotificationOption> {
    if (decision == null || decision.multi || cap <= 0) return emptyList()
    val options = decision.options
    if (options.isEmpty()) return emptyList()
    val rec = decision.recommended
    val order = if (rec != null && rec in options.indices) {
        listOf(rec) + options.indices.filter { it != rec }
    } else {
        options.indices.toList()
    }
    return order.take(cap).map { NotificationOption(it, options[it]) }
}

/** Trim a RemoteInput result into a post body; null when empty (nothing to send). */
fun replyText(raw: CharSequence?): String? =
    raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Lightly normalize markdown into plain text for the notification shade (MessagingStyle is plain
 * text). Strips fenced-code fences (keeping the code), inline backticks, heading markers, and
 * bold/italic emphasis; turns links into their label and bullet markers into "• "; collapses runs
 * of blank lines. Deliberately conservative — it should never mangle ordinary prose.
 */
fun stripMarkdownForNotification(text: String): String {
    if (text.isBlank()) return text
    var s = text.replace("\r\n", "\n").replace("\r", "\n")
    // Drop fenced-code fence lines (```lang / ```), keep the inner content.
    s = s.lines().filterNot { it.trimStart().startsWith("```") }.joinToString("\n")
    // Inline code backticks.
    s = s.replace("`", "")
    // Per-line transforms: heading markers, bullet markers.
    s = s.lines().joinToString("\n") { line ->
        line
            .replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
    }
    // Emphasis: bold before italic so **x** isn't seen as two italics.
    s = s.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    s = s.replace(Regex("\\*([^*\\n]+)\\*"), "$1")
    // Single-underscore italic. Word-boundary guarded so snake_case_identifiers (underscores
    // flanked by word chars on both outer sides) are left alone.
    s = s.replace(Regex("(?<!\\w)_([^_\\n]+)_(?!\\w)"), "$1")
    // Links [label](url) -> label.
    s = s.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    // Collapse 3+ newlines into a blank-line separator.
    s = s.replace(Regex("\n{3,}"), "\n\n")
    return s.trim()
}

/**
 * Deterministic key for a (connection, thread) pair — the single source of truth shared by the
 * notification-id derivation, the open-thread suppression signal, and the cancel-on-view path so
 * "post" and "cancel" always agree. Same `connId|threadId` string used since the first notifier.
 */
fun threadKey(connId: String, threadId: String): String = "$connId|$threadId"

/**
 * Stable notification id for a needs-you thread (#378). Both [com.atomikpanda.groundcontrol.notify
 * .AndroidNotifier] (post) and [com.atomikpanda.groundcontrol.notify.AndroidNeedsYouCanceller]
 * (cancel-on-view) derive the id through this one helper so a thread's notification can always be
 * cancelled by the same id it was posted under.
 */
fun needsYouNotificationId(connId: String, threadId: String): Int = threadKey(connId, threadId).hashCode()

/**
 * A short, glanceable button label for a decision option (#379). The notification Action title has
 * room for only ~1-3 words; the full option text overflows and is unusable. This shortens ONLY the
 * visible label — the POSTED choice (EXTRA_OPTION_TEXT) stays the full text so a phone/Watch tap
 * still sends the correct answer. Pure: first [maxWords] words, hard-capped at [maxChars], with an
 * ellipsis whenever anything was dropped. Blank in → blank out.
 */
fun optionButtonLabel(fullText: String, maxWords: Int = 3, maxChars: Int = 24): String {
    val trimmed = fullText.trim()
    if (trimmed.isEmpty()) return ""
    val words = trimmed.split(Regex("\\s+"))
    var label = words.take(maxWords).joinToString(" ")
    var truncated = words.size > maxWords
    if (label.length > maxChars) {
        label = label.take(maxChars).trimEnd()
        truncated = true
    }
    return if (truncated) "$label…" else label
}

/**
 * The full decision options as a plain-text block for the notification's MessagingStyle body
 * (#379). Now that the option BUTTONS show only a short label, the reader still needs the full
 * choices somewhere legible. Numbered 1-based, the recommended option flagged. Returns null when
 * there's no decision or no options (nothing to add). Independent of `multi` — a multi-select
 * decision renders no buttons, but its options are still worth reading.
 */
fun decisionOptionsBody(decision: Decision?): String? {
    if (decision == null) return null
    val options = decision.options
    if (options.isEmpty()) return null
    val rec = decision.recommended
    return options.mapIndexed { i, opt ->
        val flag = if (rec != null && i == rec) " (recommended)" else ""
        "${i + 1}. $opt$flag"
    }.joinToString("\n")
}

/**
 * Whether a needs-you notification for (connId, threadId) should be SUPPRESSED because that exact
 * thread is currently open in the foreground (#378). [openThreadKey] is the process-wide
 * open+foregrounded thread signal (see [com.atomikpanda.groundcontrol.notify.OpenThreadRegistry]),
 * or null when nothing is on screen / the app is backgrounded. Pure predicate — the reconciler
 * stays a thin caller.
 */
fun shouldSuppressNotification(openThreadKey: String?, connId: String, threadId: String): Boolean =
    openThreadKey != null && openThreadKey == threadKey(connId, threadId)
