// app/src/test/java/com/atomikpanda/groundcontrol/QueueCardTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Attention
import com.atomikpanda.groundcontrol.data.dto.ExternalLink
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary
import com.atomikpanda.groundcontrol.ui.queue.QueueKind
import com.atomikpanda.groundcontrol.ui.queue.QueueTier
import com.atomikpanda.groundcontrol.ui.queue.cardsFrom
import com.atomikpanda.groundcontrol.ui.queue.sortQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueCardTest {
    private val conn = WorkspaceConnection("c1", "http://h:47100", null, "ws-a")

    private fun item(id: String, att: Attention, updatedAt: String? = null) =
        WorkItemSummary(id = id, kind = "feature", title = "T-$id", phase = "ready", attention = att, updatedAt = updatedAt)

    @Test fun one_card_per_set_attention_flag() {
        val cards = cardsFrom(conn, listOf(item("wi1", Attention(needsApproval = true, blocked = true))))
        assertEquals(setOf(QueueKind.NEEDS_APPROVAL, QueueKind.BLOCKED), cards.map { it.kind }.toSet())
        assertEquals(2, cards.size)
    }

    @Test fun no_flags_yields_no_cards() {
        assertEquals(0, cardsFrom(conn, listOf(item("wi1", Attention()))).size)
    }

    @Test fun card_carries_workspace_specid_threadid_and_pr_url() {
        val it = item("wi1", Attention(needsApproval = true, needsReview = true))
            .copy(specId = "s1", threadIds = listOf("t1"),
                  externalLinks = listOf(ExternalLink(provider = "github", url = "https://gh/pr/1")))
        val cards = cardsFrom(conn, listOf(it))
        val approval = cards.first { it.kind == QueueKind.NEEDS_APPROVAL }
        val review = cards.first { it.kind == QueueKind.NEEDS_REVIEW }
        assertEquals("ws-a", approval.workspaceName)
        assertEquals("s1", approval.specId)
        assertEquals("https://gh/pr/1", review.prUrl)
    }

    @Test fun review_pr_url_only_accepts_web_schemes() {
        val web = item("web", Attention(needsReview = true))
            .copy(externalLinks = listOf(ExternalLink(provider = "github", url = "https://gh/pr/1")))
        val nonWeb = item("intent", Attention(needsReview = true))
            .copy(externalLinks = listOf(ExternalLink(provider = "github", url = "intent://x")))
        val webCard = cardsFrom(conn, listOf(web)).first { it.kind == QueueKind.NEEDS_REVIEW }
        val nonWebCard = cardsFrom(conn, listOf(nonWeb)).first { it.kind == QueueKind.NEEDS_REVIEW }
        assertEquals("https://gh/pr/1", webCard.prUrl)   // a normal web link is kept
        assertNull(nonWebCard.prUrl)                     // a non-web scheme is dropped (never opened)
    }

    @Test fun tiers_rank_blocked_and_decision_above_approval_above_review() {
        assertEquals(QueueTier.URGENT, QueueKind.BLOCKED.tier)
        assertEquals(QueueTier.URGENT, QueueKind.NEEDS_DECISION.tier)
        assertEquals(QueueTier.APPROVAL, QueueKind.NEEDS_APPROVAL.tier)
        assertEquals(QueueTier.REVIEW, QueueKind.NEEDS_REVIEW.tier)
    }

    @Test fun sort_is_tier_asc_then_oldest_first_blanks_last() {
        val a = cardsFrom(conn, listOf(item("old", Attention(needsApproval = true), updatedAt = "2026-01-01T00:00:00Z"))).first()
        val b = cardsFrom(conn, listOf(item("new", Attention(needsApproval = true), updatedAt = "2026-06-01T00:00:00Z"))).first()
        val blank = cardsFrom(conn, listOf(item("blank", Attention(needsApproval = true), updatedAt = null))).first()
        val blocked = cardsFrom(conn, listOf(item("blk", Attention(blocked = true), updatedAt = "2026-06-01T00:00:00Z"))).first()
        val sorted = sortQueue(listOf(b, blank, a, blocked))
        assertEquals(listOf("blk", "old", "new", "blank"), sorted.map { it.workItemId })
    }
}
