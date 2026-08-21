package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.notify.buildReplyNotificationEvent
import com.atomikpanda.groundcontrol.notify.needsYouNotificationId
import com.atomikpanda.groundcontrol.notify.notificationThreadUri
import com.atomikpanda.groundcontrol.notify.retiredReplyConnectionId
import com.atomikpanda.groundcontrol.notify.ReplyWorker
import com.atomikpanda.groundcontrol.notify.LegacyReplyInput
import com.atomikpanda.groundcontrol.notify.MAX_REPLY_CONTEXT_BYTES
import com.atomikpanda.groundcontrol.notify.ReplyDeliveryOutcome
import com.atomikpanda.groundcontrol.notify.classifyReplyFailure
import com.atomikpanda.groundcontrol.notify.validReplyContext
import java.nio.channels.UnresolvedAddressException
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

    @Test fun failure_notification_uses_the_resolved_canonical_connection_identity() {
        val event = buildReplyNotificationEvent(
            conn = canonical,
            threadId = "thread-1",
            fallbackSubject = "Fallback subject",
            preview = "",
            thread = null,
        )

        assertEquals(canonical.id, event.connectionId)
        assertEquals(canonical.baseUrl, event.baseUrl)
        assertEquals(canonical.workspaceName, event.workspaceName)
        assertEquals("Fallback subject", event.subject)
        assertEquals(emptyList<Message>(), event.messages)
        assertTrue(
            notificationThreadUri(event.connectionId, event.baseUrl, event.threadId)
                .contains("connection=${canonical.id}"),
        )
    }

    @Test fun resolved_completion_cancels_only_the_retired_notification_identity() {
        assertEquals("retired", retiredReplyConnectionId("retired", canonical))
        assertEquals(null, retiredReplyConnectionId(canonical.id, canonical))
    }

    @Test fun safe_pre_transmission_address_failure_is_not_uncertain() {
        assertEquals(ReplyDeliveryOutcome.SafePreTransmissionFailure, classifyReplyFailure(UnresolvedAddressException()))
    }

    @Test fun work_request_contains_only_opaque_action_key() {
        val data = ReplyWorker.workData("opaque-capability")
        assertEquals(setOf(ReplyWorker.K_ACTION_KEY), data.keyValueMap.keys)
        assertEquals("opaque-capability", data.getString(ReplyWorker.K_ACTION_KEY))
    }

    @Test fun queued_legacy_work_contract_is_detected_instead_of_being_silently_ignored() {
        val legacy = ReplyWorker.legacyReplyInput(
            ReplyWorker.legacyWorkData("alias", "thread", "exact reply", "subject", "workspace", "https://example"),
        )

        assertEquals(
            LegacyReplyInput("alias", "thread", "exact reply", "subject", "workspace", "https://example"),
            legacy,
        )
        assertEquals(null, ReplyWorker.legacyReplyInput(ReplyWorker.workData("opaque-capability")))
    }

    @Test fun bounded_context_uses_bytes_not_characters() {
        assertTrue(validReplyContext("Δ".repeat(MAX_REPLY_CONTEXT_BYTES / 2)))
        assertTrue(!validReplyContext("x".repeat(MAX_REPLY_CONTEXT_BYTES + 1)))
    }
}
