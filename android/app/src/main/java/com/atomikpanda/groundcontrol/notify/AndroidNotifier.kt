package com.atomikpanda.groundcontrol.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.atomikpanda.groundcontrol.MainActivity
import java.net.URLEncoder

class AndroidNotifier(private val context: Context) : Notifier {
    override fun notify(event: NeedsYouEvent) {
        val link = "groundcontrol://thread?workspace=" +
            URLEncoder.encode(event.baseUrl, "UTF-8") + "&id=" + event.threadId
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, link.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.NEEDS_YOU)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(event.workspaceName.ifBlank { "Ground Control" })
            .setContentText(if (event.subject.isBlank()) event.preview else "${event.subject} — ${event.preview}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.preview))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val id = (event.connectionId + "|" + event.threadId).hashCode()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
