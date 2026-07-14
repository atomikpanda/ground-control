// app/src/main/java/com/atomikpanda/groundcontrol/ui/queue/QueueCard.kt
package com.atomikpanda.groundcontrol.ui.queue

import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.data.dto.Decision
import com.atomikpanda.groundcontrol.data.dto.SpecRecord
import com.atomikpanda.groundcontrol.data.dto.Thread
import com.atomikpanda.groundcontrol.ui.home.displayName

/** Urgency tiers for the Queue. Lower ordinal = higher urgency. */
enum class QueueTier { URGENT, APPROVAL }

/**
 * One review/decision card in the Queue v2 feed. The Queue sources `needs_review`
 * spec **chunks** (prose sections + acceptance criteria + open questions) and open
 * thread **decisions**, one card at a time. Every variant carries the identity a
 * card stack needs: which workspace, a stable [key] for dedupe + list identity, an
 * urgency [tier], and a [waitingSince] proxy for oldest-first ordering within a tier.
 */
sealed interface QueueV2Card {
    val connectionId: String
    val workspaceName: String
    val key: String
    val tier: QueueTier
    val waitingSince: String
}

/** The prose sections a `needs_review` spec exposes as review cards. Ids match the
 *  serve `PROSE_UNIT_IDS` surfaced in `build_review`'s context / `prose_verdicts`
 *  (problem/user_story/approach are parsed from the body; non_goals/risks are lists). */
enum class ProseSection(val id: String, val label: String, val bodyHeading: String?) {
    PROBLEM("problem", "Problem", "Problem"),
    USER_STORY("user_story", "User story", "User story"),
    APPROACH("approach", "Approach", "Approach"),
    NON_GOALS("non_goals", "Non-goals", null),
    RISKS("risks", "Risks", null),
}

/** One prose section of a spec under review. [verdict] is the reviewer's current
 *  call ("unreviewed" | "approved" | "flagged"); [comment] is an optional flag note. */
/** Per-spec review-card metadata shown on every review card of a spec (#B on the Queue): the spec
 *  title, the linked WorkItem's kind (feature/bug/chore), and the repos the work touches. */
data class SpecCardMeta(
    val title: String = "",
    val workItemKind: String? = null,
    val affectedRepos: List<String> = emptyList(),
)

data class ProseCard(
    override val connectionId: String,
    override val workspaceName: String,
    val specId: String,
    val sectionId: String,
    val sectionLabel: String,
    val text: String,
    val verdict: String,
    val comment: String? = null,
    val meta: SpecCardMeta = SpecCardMeta(),
    override val waitingSince: String = "",
) : QueueV2Card {
    override val tier: QueueTier get() = QueueTier.APPROVAL
    override val key: String get() = "prose:$connectionId:$specId:$sectionId"
}

/** One acceptance criterion inside a [CriteriaCard]. */
data class CriterionItem(
    val id: String,
    val text: String,
    val verdict: String,
    val comment: String? = null,
)

/** All acceptance criteria of a spec under review, gathered into one multi-item card. */
data class CriteriaCard(
    override val connectionId: String,
    override val workspaceName: String,
    val specId: String,
    val items: List<CriterionItem>,
    val meta: SpecCardMeta = SpecCardMeta(),
    override val waitingSince: String = "",
) : QueueV2Card {
    override val tier: QueueTier get() = QueueTier.APPROVAL
    override val key: String get() = "criteria:$connectionId:$specId"
}

/** One open question inside a [QuestionsCard]. */
data class QuestionItem(
    val id: String,
    val text: String,
    val answer: String? = null,
)

/** The still-unanswered open questions of a spec under review, as one multi-item card. */
data class QuestionsCard(
    override val connectionId: String,
    override val workspaceName: String,
    val specId: String,
    val items: List<QuestionItem>,
    val meta: SpecCardMeta = SpecCardMeta(),
    override val waitingSince: String = "",
) : QueueV2Card {
    override val tier: QueueTier get() = QueueTier.APPROVAL
    override val key: String get() = "questions:$connectionId:$specId"
}

/** An open decision on a thread — the prompt [text] plus its [decision] options,
 *  rendered inline (reuses the `Decision` shape from the messages surface). */
data class DecisionCard(
    override val connectionId: String,
    override val workspaceName: String,
    val threadId: String,
    val text: String,
    val decision: Decision,
    override val waitingSince: String = "",
) : QueueV2Card {
    override val tier: QueueTier get() = QueueTier.URGENT
    override val key: String get() = "decision:$connectionId:$threadId"
}

/** Split a spec markdown body into `{heading: prose}` by `## ` headings — the GC
 *  mirror of serve's `parse_body_sections` (an `## ` line opens a section; `### `+
 *  stays in-section). Duplicate headings: last occurrence wins. */
internal fun parseBodySections(body: String): Map<String, String> {
    val sections = linkedMapOf<String, String>()
    var current: String? = null
    val buf = StringBuilder()
    fun flush() { current?.let { sections[it] = buf.toString().trim() } }
    for (line in body.lines()) {
        if (line.startsWith("## ")) {
            flush()
            current = line.removePrefix("## ").trim()
            buf.setLength(0)
        } else if (current != null) {
            buf.append(line).append('\n')
        }
    }
    flush()
    return sections
}

/** Explode one `needs_review` spec into the review cards that STILL NEED the operator: one
 *  [ProseCard] per non-empty prose section that isn't already approved, one [CriteriaCard] when
 *  any acceptance criterion isn't yet approved, and one [QuestionsCard] only when unanswered
 *  questions remain. Cards off the SAVED verdicts (not just in-memory session state), so an
 *  already-approved chunk doesn't reappear when the ViewModel is recreated — the approval sticks.
 *  Verdicts/comments come from the spec's `prose_verdicts` map + each criterion's `comment`. */
fun cardsFromSpec(conn: WorkspaceConnection, spec: SpecRecord): List<QueueV2Card> {
    val ws = conn.displayName()
    val since = spec.updatedAt ?: ""
    val sections = parseBodySections(spec.body)
    val meta = SpecCardMeta(
        title = spec.title,
        workItemKind = spec.workItemKind,
        affectedRepos = spec.affectedRepos,
    )
    return buildList {
        for (sec in ProseSection.entries) {
            val text = when (sec) {
                ProseSection.NON_GOALS -> spec.nonGoals.joinToString("\n") { "• $it" }
                ProseSection.RISKS -> spec.risks.joinToString("\n") { "• $it" }
                else -> sections[sec.bodyHeading].orEmpty()
            }.trim()
            if (text.isBlank()) continue
            val pv = spec.proseVerdicts[sec.id]
            if (pv?.verdict == "approved") continue   // already reviewed → don't re-card it
            add(
                ProseCard(
                    connectionId = conn.id, workspaceName = ws, specId = spec.id,
                    sectionId = sec.id, sectionLabel = sec.label, text = text,
                    verdict = pv?.verdict ?: "unreviewed", comment = pv?.comment, meta = meta, waitingSince = since,
                )
            )
        }
        // Only card the criteria while at least one isn't approved (a fully-approved set is done).
        if (spec.acceptanceCriteria.any { it.verdict != "approved" }) {
            add(
                CriteriaCard(
                    connectionId = conn.id, workspaceName = ws, specId = spec.id,
                    items = spec.acceptanceCriteria.map { CriterionItem(it.id, it.text, it.verdict, it.comment) },
                    meta = meta, waitingSince = since,
                )
            )
        }
        val unanswered = spec.openQuestions.filter { it.answer.isNullOrBlank() }
        if (unanswered.isNotEmpty()) {
            add(
                QuestionsCard(
                    connectionId = conn.id, workspaceName = ws, specId = spec.id,
                    items = unanswered.map { QuestionItem(it.id, it.text, it.answer) },
                    meta = meta, waitingSince = since,
                )
            )
        }
    }
}

/** A [DecisionCard] for a thread whose latest decision message is still open, or null
 *  when the thread carries no pending decision (mirrors the MOS-225 `pendingDecision`:
 *  the last message with a structured `decision` is the current prompt). */
fun decisionCardFrom(conn: WorkspaceConnection, thread: Thread): DecisionCard? {
    val msg = thread.messages.lastOrNull { it.decision != null } ?: return null
    val decision = msg.decision ?: return null
    return DecisionCard(
        connectionId = conn.id,
        workspaceName = conn.displayName(),
        threadId = thread.id,
        text = msg.text,
        decision = decision,
        waitingSince = thread.updatedAt ?: thread.createdAt ?: "",
    )
}

/** Urgency order: tier asc (URGENT first), then oldest-waiting first; unknown/blank
 *  timestamps sort last. Stable, so cards from one spec keep their emitted order. */
internal val queueComparator: Comparator<QueueV2Card> =
    compareBy<QueueV2Card> { it.tier.ordinal }.thenBy { it.waitingSince.ifBlank { "￿" } }

fun sortQueue(cards: List<QueueV2Card>): List<QueueV2Card> = cards.sortedWith(queueComparator)
