package com.atomikpanda.groundcontrol.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import com.atomikpanda.groundcontrol.MainActivity
import java.net.URLEncoder

/**
 * Renders a needs-you notification as a `NotificationCompat.MessagingStyle` conversation: a "me"
 * [Person] for human messages and an agent [Person] (keyed by connId, named by workspace) for agent
 * messages, showing the last few thread messages as context. Adds an inline free-text reply
 * (RemoteInput) and, for a single-select decision, up to [MAX_OPTION_ACTIONS] recommended-first
 * option buttons. Pure render step — enrichment (the thread fetch) happens in the reconciler.
 */
class AndroidNotifier(private val context: Context) : Notifier {

    override fun notify(event: NeedsYouEvent) = render(event, errorLine = null)

    /** Re-notify after a failed reply: surface the attempted text (so it isn't lost) + keep the
     *  reply action for a manual retry. */
    fun notifyReplyError(event: NeedsYouEvent, failedText: String) =
        render(event, errorLine = failedText)

    private fun render(event: NeedsYouEvent, errorLine: String?) {
        val notifId = needsYouNotificationId(event.connectionId, event.threadId)
        val agentName = event.workspaceName.ifBlank { "Ground Control" }
        val title = event.subject.ifBlank { agentName }
        val me = Person.Builder().setName("You").setKey(KEY_ME).build()
        val agent = Person.Builder().setName(agentName).setKey(event.connectionId).build()

        // Deep-link tap intent (unchanged): opens the thread in-app.
        val link = "groundcontrol://thread?workspace=" +
            URLEncoder.encode(event.baseUrl, "UTF-8") + "&id=" + event.threadId
        val contentIntent = PendingIntent.getActivity(
            context, link.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(link)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Long-lived conversation shortcut → conversation surface + LocusId.
        val shortcutId = runCatching {
            ConversationShortcuts.push(context, event.connectionId, event.threadId, link, agent, title)
        }.getOrNull()

        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title)
        val now = System.currentTimeMillis()
        val recent = recentMessages(event.messages)
        if (recent.isEmpty()) {
            style.addMessage(stripMarkdownForNotification(event.preview), now, agent)
        } else {
            val stamps = messageTimestamps(recent.map { it.createdAt }, now)
            recent.forEachIndexed { i, m ->
                val isHuman = m.role == "human"
                val text = if (isHuman) m.text else stripMarkdownForNotification(m.text)
                style.addMessage(text, stamps[i], if (isHuman) me else agent)
            }
        }
        // Full option list in the chat body so the reader can still see the full choices now that
        // the option BUTTONS below show only a short label (#379). Timestamped now+1 (and the error
        // now+2) so these trailing lines always sort AFTER the conversation and in a fixed order —
        // a thread message with an unparseable timestamp also falls back to `now`, so `now` here
        // would tie and leave the order dependent on TimSort stability rather than guaranteed.
        decisionOptionsBody(event.decision)?.let { style.addMessage(it, now + 1, agent) }
        if (errorLine != null) {
            style.addMessage("⚠️ Couldn't send: $errorLine", now + 2, me)
        }

        val builder = NotificationCompat.Builder(context, NotificationChannels.NEEDS_YOU)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        if (shortcutId != null) {
            builder.setShortcutId(shortcutId).setLocusId(LocusIdCompat(shortcutId))
        }

        // Reply first so it's always within the standard 3-action budget alongside the option
        // buttons. (Android's standard template renders at most 3 actions; a persistent reply +
        // MAX_OPTION_ACTIONS option buttons must fit — see MAX_OPTION_ACTIONS.)
        builder.addAction(replyAction(event))
        decisionActionOptions(event.decision, MAX_OPTION_ACTIONS).forEach { opt ->
            builder.addAction(optionAction(event, opt.text))
        }

        runCatching { NotificationManagerCompat.from(context).notify(notifId, builder.build()) }
    }

    private fun replyAction(event: NeedsYouEvent): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        val pi = replyPendingIntent(event, tag = "reply", optionText = null, mutable = true)
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun optionAction(event: NeedsYouEvent, optionText: String): NotificationCompat.Action {
        // POST the FULL option text (EXTRA_OPTION_TEXT, via replyPendingIntent) so a phone/Watch tap
        // sends the correct full choice; only the VISIBLE label is shortened (#379).
        val pi = replyPendingIntent(event, tag = "opt_" + optionText.hashCode(), optionText = optionText, mutable = false)
        return NotificationCompat.Action.Builder(0, optionButtonLabel(optionText), pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE)
            .setShowsUserInterface(false)
            .build()
    }

    private fun replyPendingIntent(
        event: NeedsYouEvent,
        tag: String,
        optionText: String?,
        mutable: Boolean,
    ): PendingIntent {
        val discriminator = event.connectionId + "|" + event.threadId + ":" + tag
        val intent = Intent(context, ReplyReceiver::class.java).apply {
            // Distinct action per (thread, tag) so PendingIntents don't collide (extras are ignored
            // for PendingIntent equality).
            action = "com.atomikpanda.groundcontrol.REPLY:$discriminator"
            putExtra(ReplyReceiver.EXTRA_CONN_ID, event.connectionId)
            putExtra(ReplyReceiver.EXTRA_THREAD_ID, event.threadId)
            putExtra(ReplyReceiver.EXTRA_SUBJECT, event.subject)
            putExtra(ReplyReceiver.EXTRA_WORKSPACE, event.workspaceName)
            putExtra(ReplyReceiver.EXTRA_BASE_URL, event.baseUrl)
            optionText?.let { putExtra(ReplyReceiver.EXTRA_OPTION_TEXT, it) }
        }
        val mutability = when {
            // FLAG_MUTABLE (API 31+) lets the system inject the RemoteInput reply text.
            mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            // Pre-31 PendingIntents are mutable by default; must NOT set FLAG_IMMUTABLE here.
            mutable -> 0
            else -> PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context, discriminator.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    companion object {
        const val KEY_ME = "me"

        /**
         * Option buttons rendered alongside the always-present free-text reply. Android's standard
         * notification template shows at most 3 actions, and the reply must always be visible, so
         * the reliable maximum is 2 option buttons + reply = 3. (3 options + reply = 4 would drop
         * one on the phone template; the operator approved 2 as the fallback — q1.)
         */
        const val MAX_OPTION_ACTIONS = 2
    }
}
