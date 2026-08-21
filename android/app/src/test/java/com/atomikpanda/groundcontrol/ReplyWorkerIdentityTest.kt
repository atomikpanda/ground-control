package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.notify.ReplyWorker
import com.atomikpanda.groundcontrol.notify.buildFailedReplyNotificationEvent
import com.atomikpanda.groundcontrol.notify.buildReplyNotificationEvent
import com.atomikpanda.groundcontrol.notify.needsYouNotificationId
import com.atomikpanda.groundcontrol.notify.notificationThreadUri
import com.atomikpanda.groundcontrol.notify.retiredReplyConnectionIds
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyWorkerIdentityTest {
    private val canonical = WorkspaceConnection(
        id = "canonical",
        baseUrl = "https://relay.example/hosts/host-1/workspaces/ws-1",
        workspaceName = "workspace",
        legacyConnectionIds = listOf("retired"),
    )

    @Test fun success_notification_uses_the_resolved_canonical_connection_identity() {
        val returned = Thread(
            id = "thread-1",
            subject = "Returned subject",
            updatedAt = "2026-08-20T12:00:00Z",
            messages = listOf(Message("message-1", role = "human", text = "sent")),
        )

        val event = buildReplyNotificationEvent(
            conn = canonical,
            threadId = returned.id,
            fallbackSubject = "Fallback subject",
            preview = "sent",
            thread = returned,
        )

        assertEquals(canonical.id, event.connectionId)
        assertEquals(canonical.baseUrl, event.baseUrl)
        assertEquals("Returned subject", event.subject)
        assertEquals(returned.messages, event.messages)
        assertEquals(
            needsYouNotificationId(canonical.id, returned.id),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertNotEquals(
            needsYouNotificationId("retired", returned.id),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertTrue(
            notificationThreadUri(event.connectionId, event.baseUrl, event.threadId)
                .contains("connection=${canonical.id}"),
        )
    }

    @Test fun failed_retired_reply_updates_its_single_retry_notification() {
        val event = buildFailedReplyNotificationEvent(
            persistedConnectionId = "retired",
            conn = canonical,
            threadId = "thread-1",
            fallbackSubject = "Fallback subject",
            preview = "",
        )

        assertEquals("retired", event.connectionId)
        assertEquals(canonical.baseUrl, event.baseUrl)
        assertEquals(canonical.workspaceName, event.workspaceName)
        assertEquals("Fallback subject", event.subject)
        assertEquals(emptyList<Message>(), event.messages)
        assertEquals(
            needsYouNotificationId("retired", event.threadId),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertNotEquals(
            needsYouNotificationId(canonical.id, event.threadId),
            needsYouNotificationId(event.connectionId, event.threadId),
        )
        assertEquals(
            ReplyWorker.uniqueWorkName("retired", event.threadId),
            ReplyWorker.uniqueWorkName(event.connectionId, event.threadId),
        )
        assertNotEquals(
            ReplyWorker.uniqueWorkName(canonical.id, event.threadId),
            ReplyWorker.uniqueWorkName(event.connectionId, event.threadId),
        )
        assertTrue(
            notificationThreadUri(event.connectionId, event.baseUrl, event.threadId)
                .contains("connection=retired"),
        )
    }

    @Test fun failed_reply_moves_one_immediate_retry_to_a_new_keep_work_name() {
        val running = ReplyWorker.uniqueWorkName("retired", "thread-1")
        val retry = ReplyWorker.uniqueWorkName("retired", "thread-1", retryAttempt = 1)

        assertNotEquals(running, retry)
        assertEquals(retry, ReplyWorker.uniqueWorkName("retired", "thread-1", retryAttempt = 1))
        assertEquals(ExistingWorkPolicy.KEEP, ReplyWorker.enqueuePolicy)
    }

    @Test fun successful_canonical_retry_cancels_all_retired_notification_identities() {
        val canonicalWithMultipleRetiredIds = canonical.copy(
            legacyConnectionIds = listOf("retired", "older-retired", canonical.id),
        )

        assertEquals(
            listOf("retired", "older-retired"),
            retiredReplyConnectionIds(canonicalWithMultipleRetiredIds, replySucceeded = true),
        )
    }

    @Test fun failed_completion_preserves_retired_notification_identities_for_manual_retry() {
        assertEquals(
            emptyList<String>(),
            retiredReplyConnectionIds(canonical, replySucceeded = false),
        )
    }
}
