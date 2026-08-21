package com.atomikpanda.groundcontrol.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.atomikpanda.groundcontrol.data.dto.Decision
import java.util.concurrent.atomic.AtomicBoolean

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
        // Scheduling contains no DataStore work and returns immediately. Finish the broadcast after
        // WorkManager confirms persistence, but never retain it past Android's broadcast window.
        val pending = goAsync()
        val finished = AtomicBoolean()
        val finish = Runnable {
            if (finished.compareAndSet(false, true)) pending.finish()
        }
        try {
            val operation = ReplyWorker.enqueue(
                context = context,
                connId = connId,
                threadId = threadId,
                text = text,
                subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "",
                workspace = intent.getStringExtra(EXTRA_WORKSPACE) ?: "",
                baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: "",
                notificationVersion = intent.getStringExtra(EXTRA_NOTIFICATION_VERSION) ?: "",
                retryAttempt = intent.getIntExtra(EXTRA_RETRY_ATTEMPT, 0),
                decision = intent.decision(),
            )
            operation.result.addListener(finish, ContextCompat.getMainExecutor(context))
            Handler(Looper.getMainLooper()).postDelayed(finish, MAX_BROADCAST_HANDOFF_MS)
        } catch (_: Exception) {
            finish.run()
        }
    }

    private fun Intent.decision(): Decision? {
        val options = getStringArrayListExtra(EXTRA_DECISION_OPTIONS) ?: return null
        return Decision(
            options = options,
            recommended = getIntExtra(EXTRA_DECISION_RECOMMENDED, -1).takeIf { it >= 0 },
            allowFreeText = getBooleanExtra(EXTRA_DECISION_ALLOW_FREE_TEXT, true),
            multi = getBooleanExtra(EXTRA_DECISION_MULTI, false),
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
        const val EXTRA_RETRY_ATTEMPT = "retry_attempt"
        const val EXTRA_NOTIFICATION_VERSION = "notification_version"
        const val EXTRA_DECISION_OPTIONS = "decision_options"
        const val EXTRA_DECISION_RECOMMENDED = "decision_recommended"
        const val EXTRA_DECISION_ALLOW_FREE_TEXT = "decision_allow_free_text"
        const val EXTRA_DECISION_MULTI = "decision_multi"
        private const val MAX_BROADCAST_HANDOFF_MS = 9_000L
    }
}
