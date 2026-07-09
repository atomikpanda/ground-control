package com.atomikpanda.groundcontrol.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Receives an inline direct-reply (RemoteInput free text) or an option-button tap from a needs-you
 * notification, then hands the post off to an expedited [ReplyWorker] so it survives Doze /
 * process-death (ac6). Never posts on the receiver thread. Not exported (registered in the
 * manifest with android:exported="false").
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connId = intent.getStringExtra(EXTRA_CONN_ID) ?: return
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: return
        // Free-text reply (also the WearOS path) takes precedence; otherwise a raw option tap.
        val fromRemote = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)
        val text = replyText(fromRemote)
            ?: intent.getStringExtra(EXTRA_OPTION_TEXT)?.takeIf { it.isNotBlank() }
            ?: return
        ReplyWorker.enqueue(
            context = context,
            connId = connId,
            threadId = threadId,
            text = text,
            subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "",
            workspace = intent.getStringExtra(EXTRA_WORKSPACE) ?: "",
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: "",
        )
    }

    companion object {
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_CONN_ID = "conn_id"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_OPTION_TEXT = "option_text"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_BASE_URL = "base_url"
    }
}
