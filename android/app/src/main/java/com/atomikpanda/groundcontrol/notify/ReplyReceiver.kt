package com.atomikpanda.groundcontrol.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.atomikpanda.groundcontrol.data.ConnectionsRepository
import com.atomikpanda.groundcontrol.data.buildJson
import com.atomikpanda.groundcontrol.data.dto.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

interface ReplyIntake {
    suspend fun submit(intent: Intent): Boolean
}

internal class ReplyOutboxIntake private constructor(private val outbox: ReplyOutbox) : ReplyIntake {
    constructor(context: Context) : this(
        ReplyOutbox(
            database = NotifiedDatabase.get(context),
            scheduler = WorkManagerReplyScheduler(context),
            currentConnections = { ConnectionsRepository(context).snapshot() },
            notificationActionHandler = { submission ->
                AndroidNeedsYouCanceller(context).cancel(submission.connectionId, submission.threadId)
            },
        ),
    )

    internal constructor(outbox: ReplyOutbox, testOnly: Unit = Unit) : this(outbox)

    override suspend fun submit(intent: Intent): Boolean {
        val submission = intent.toReplySubmission() ?: return false
        return submit(submission)
    }

    internal suspend fun submit(submission: ReplySubmission): Boolean = outbox.submit(submission)
}

/**
 * Converts the tap to bounded WorkData and waits only for WorkManager's durable enqueue. Room
 * migration/reset and network delivery happen in [ReplyIntakeWorker], after the broadcast ends.
 */
internal class WorkManagerReplyIntake(private val context: Context) : ReplyIntake {
    override suspend fun submit(intent: Intent): Boolean {
        val submission = intent.toReplySubmission() ?: return false
        ReplyIntakeWorker.enqueue(context, submission).result.get()
        return true
    }
}

/** Receives actions off the broadcast/main thread and releases [PendingResult] after durable enqueue. */
class ReplyReceiver @JvmOverloads constructor(
    private val intakeFactory: (Context) -> ReplyIntake = { WorkManagerReplyIntake(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                intakeFactory(context.applicationContext).submit(intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_CONN_ID = "conn_id"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_NOTIFICATION_VERSION = "notification_version"
        const val EXTRA_REPLY_CAPABILITY = "reply_capability"
        const val EXTRA_OPTION_TEXT = "option_text"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_BASE_URL = "base_url"
    }
}

internal fun Intent.toReplySubmission(): ReplySubmission? {
    val connectionId = getStringExtra(ReplyReceiver.EXTRA_CONN_ID) ?: return null
    val threadId = getStringExtra(ReplyReceiver.EXTRA_THREAD_ID) ?: return null
    val version = getStringExtra(ReplyReceiver.EXTRA_NOTIFICATION_VERSION) ?: return null
    val capability = getStringExtra(ReplyReceiver.EXTRA_REPLY_CAPABILITY) ?: return null
    val decisionJson = getStringExtra(ReplyReceiver.EXTRA_DECISION)
    val decision = decisionJson?.let { runCatching { buildJson().decodeFromString<Decision>(it) }.getOrNull() }
    if (decisionJson != null && decision == null) return null
    val remote = RemoteInput.getResultsFromIntent(this)?.getCharSequence(ReplyReceiver.KEY_REPLY_TEXT)
    val freeText = remote?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    val option = getStringExtra(ReplyReceiver.EXTRA_OPTION_TEXT)?.takeIf { it.isNotEmpty() }
    val kind = if (freeText != null) ReplyInputKind.FREE_TEXT else ReplyInputKind.OPTION
    val text = freeText ?: option ?: return null
    if (kind == ReplyInputKind.FREE_TEXT && decision?.allowFreeText == false) return null
    if (kind == ReplyInputKind.OPTION && decision != null && text !in decision.options) return null
    val subject = getStringExtra(ReplyReceiver.EXTRA_SUBJECT).orEmpty()
    val workspace = getStringExtra(ReplyReceiver.EXTRA_WORKSPACE).orEmpty()
    val baseUrl = getStringExtra(ReplyReceiver.EXTRA_BASE_URL).orEmpty()
    if (!validReplyContext(capability, connectionId, threadId, version, text, subject, workspace, baseUrl, decisionJson.orEmpty())) return null
    return ReplySubmission(
        actionKey = capability,
        connectionId = connectionId,
        threadId = threadId,
        notificationVersion = version,
        replyText = text,
        inputKind = kind,
        subject = subject,
        workspace = workspace,
        baseUrl = baseUrl,
        decision = decision,
        decisionJson = decisionJson,
    )
}
