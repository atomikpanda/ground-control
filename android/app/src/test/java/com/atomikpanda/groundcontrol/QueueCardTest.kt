// app/src/test/java/com/atomikpanda/groundcontrol/QueueCardTest.kt
package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.Message
import com.atomikpanda.groundcontrol.data.dto.ProseVerdictDto
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.data.dto.ReviewQuestion
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.queue.CriteriaCard
import com.atomikpanda.groundcontrol.ui.queue.DecisionCard
import com.atomikpanda.groundcontrol.ui.queue.ProseCard
import com.atomikpanda.groundcontrol.ui.queue.QuestionsCard
import com.atomikpanda.groundcontrol.ui.queue.QueueTier
import com.atomikpanda.groundcontrol.ui.queue.cardsFromSpec
import com.atomikpanda.groundcontrol.ui.queue.decisionCardFrom
import com.atomikpanda.groundcontrol.ui.queue.sortQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCardTest {
    private val conn = WorkspaceConnection("c1", "http://h:47100", null, "ws-a")

    private val body = """
        ## Problem

        The problem.

        ## User story

        As a user I want X.

        ## Approach

        Do the thing.
    """.trimIndent()

    private fun spec(
        id: String = "s1",
        body: String = this.body,
        criteria: List<ReviewCriterion> = listOf(
            ReviewCriterion("ac1", "AC one", "approved"),
            ReviewCriterion("ac2", "AC two", "flagged", comment = "fix"),
            ReviewCriterion("ac3", "AC three", "unreviewed"),
        ),
        questions: List<ReviewQuestion> = listOf(
            ReviewQuestion("q1", "answered?", answer = "yes"),
            ReviewQuestion("q2", "open?", answer = null),
        ),
        nonGoals: List<String> = listOf("not this"),
        risks: List<String> = listOf("might break"),
        proseVerdicts: Map<String, ProseVerdictDto> = emptyMap(),
        updatedAt: String? = "2026-06-01T00:00:00Z",
    ) = SpecRecord(
        id = id, title = "T", status = "needs_review", body = body,
        acceptanceCriteria = criteria, openQuestions = questions,
        nonGoals = nonGoals, risks = risks, proseVerdicts = proseVerdicts, updatedAt = updatedAt,
    )

    @Test fun needs_review_spec_explodes_into_prose_criteria_and_questions_cards() {
        val cards = cardsFromSpec(conn, spec())
        // 5 prose sections (in canonical order) + 1 criteria + 1 questions
        val prose = cards.filterIsInstance<ProseCard>()
        assertEquals(
            listOf("problem", "user_story", "approach", "non_goals", "risks"),
            prose.map { it.sectionId },
        )
        assertEquals("ws-a", prose.first().workspaceName)
        assertTrue(prose.first { it.sectionId == "problem" }.text.contains("The problem."))
        assertTrue(prose.first { it.sectionId == "non_goals" }.text.contains("not this"))

        val criteria = cards.filterIsInstance<CriteriaCard>().single()
        assertEquals(3, criteria.items.size)

        val questions = cards.filterIsInstance<QuestionsCard>().single()
        assertEquals(listOf("q2"), questions.items.map { it.id })   // only the unanswered one
    }

    @Test fun empty_prose_sections_and_absent_lists_are_skipped() {
        val cards = cardsFromSpec(
            conn,
            spec(body = "## Problem\n\nonly this\n", criteria = emptyList(), questions = emptyList(),
                nonGoals = emptyList(), risks = emptyList()),
        )
        assertEquals(listOf("problem"), cards.filterIsInstance<ProseCard>().map { it.sectionId })
        assertTrue(cards.none { it is CriteriaCard })
        assertTrue(cards.none { it is QuestionsCard })
    }

    @Test fun criteria_card_carries_each_items_verdict_and_comment() {
        val criteria = cardsFromSpec(conn, spec()).filterIsInstance<CriteriaCard>().single()
        val flagged = criteria.items.first { it.id == "ac2" }
        assertEquals("flagged", flagged.verdict)
        assertEquals("fix", flagged.comment)
        assertEquals("approved", criteria.items.first { it.id == "ac1" }.verdict)
    }

    @Test fun prose_card_reflects_the_specs_prose_verdict() {
        val cards = cardsFromSpec(
            conn,
            spec(proseVerdicts = mapOf("approach" to ProseVerdictDto("flagged", "unclear"))),
        )
        val approach = cards.filterIsInstance<ProseCard>().first { it.sectionId == "approach" }
        assertEquals("flagged", approach.verdict)
        assertEquals("unclear", approach.comment)
        // an untouched section defaults to unreviewed
        assertEquals("unreviewed", cards.filterIsInstance<ProseCard>().first { it.sectionId == "problem" }.verdict)
    }

    @Test fun approved_prose_sections_are_not_re_carded() {
        val cards = cardsFromSpec(
            conn,
            spec(proseVerdicts = mapOf(
                "problem" to ProseVerdictDto("approved", null),
                "approach" to ProseVerdictDto("flagged", "unclear"),
            )),
        )
        val prose = cards.filterIsInstance<ProseCard>().map { it.sectionId }
        assertTrue(prose.none { it == "problem" })   // approved → skipped (sticks across sessions)
        assertTrue(prose.contains("approach"))       // flagged → still needs the operator
        assertTrue(prose.contains("user_story"))     // unreviewed → still shown
    }

    @Test fun criteria_card_is_skipped_once_all_criteria_approved() {
        val allApproved = listOf(
            ReviewCriterion("ac1", "AC one", "approved"),
            ReviewCriterion("ac2", "AC two", "approved"),
        )
        assertTrue(cardsFromSpec(conn, spec(criteria = allApproved)).none { it is CriteriaCard })
        // a single not-yet-approved criterion keeps the card
        val mixed = spec(criteria = allApproved + ReviewCriterion("ac3", "AC three", "unreviewed"))
        assertTrue(cardsFromSpec(conn, mixed).any { it is CriteriaCard })
    }

    private fun threadWithDecision() = Thread(
        id = "t1", updatedAt = "2026-06-02T00:00:00Z",
        messages = listOf(
            Message(id = "m1", role = "agent", text = "context"),
            Message(id = "m2", role = "agent", text = "Pick one", kind = "decision",
                decision = Decision(options = listOf("X", "Y"), recommended = 0)),
        ),
    )

    @Test fun thread_with_a_pending_decision_yields_a_decision_card() {
        val card = decisionCardFrom(conn, threadWithDecision())!!
        assertEquals("t1", card.threadId)
        assertEquals("Pick one", card.text)
        assertEquals(listOf("X", "Y"), card.decision.options)
        assertEquals(QueueTier.URGENT, card.tier)
    }

    @Test fun thread_without_a_decision_yields_null() {
        val plain = Thread(id = "t2", messages = listOf(Message(id = "m1", role = "agent", text = "hi")))
        assertNull(decisionCardFrom(conn, plain))
    }

    @Test fun ordering_puts_decisions_before_spec_review_chunks() {
        val specCards = cardsFromSpec(conn, spec())
        val decision = decisionCardFrom(conn, threadWithDecision())!!
        val sorted = sortQueue(specCards + decision)
        assertEquals(QueueTier.URGENT, sorted.first().tier)
        assertTrue(sorted.first() is DecisionCard)
        assertTrue(sorted.drop(1).all { it.tier == QueueTier.APPROVAL })
    }
}
