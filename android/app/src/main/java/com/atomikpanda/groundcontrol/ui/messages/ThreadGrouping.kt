package com.atomikpanda.groundcontrol.ui.messages

import com.atomikpanda.groundcontrol.data.dto.ThreadSummary
import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary

/** Header shown for threads that belong to no WorkItem. */
const val OTHER_GROUP_TITLE = "Other"

/**
 * One messages-surface section: a WorkItem's threads, or the catch-all "Other" bucket.
 *
 * @param workItemId the owning WorkItem's id, or null for the "Other" group.
 * @param title header label — the WorkItem's title, or [OTHER_GROUP_TITLE].
 * @param kind the WorkItem's kind (feature/bug/chore/question); "" for "Other".
 * @param threads this group's threads, ordered most-recent-first.
 * @param hasAttention rolled up from the group's threads (see [ThreadSummary.needsAttention]).
 */
data class WorkItemThreadGroup(
    val workItemId: String?,
    val title: String,
    val kind: String,
    val threads: List<ThreadSummary>,
    val hasAttention: Boolean,
)

/** A thread contributes to its group's attention indicator when it is awaiting a reply,
 *  carries an unhandled agent event, or has an unanswered needs-you / decision prompt — i.e.
 *  anything the operator should notice without expanding the group. */
internal fun ThreadSummary.needsAttention(): Boolean =
    awaitingReply || awaitingAgentEvent || needsYou || needsDecision

/**
 * Group thread summaries by their owning WorkItem for the messages surface.
 *
 * - One group per WorkItem that owns at least one of [threads]; its header carries the item's
 *   title + kind (looked up from [items] by id).
 * - Threads whose [ThreadSummary.workItemId] is null — or references an item not present in
 *   [items] — collapse into a single [OTHER_GROUP_TITLE] group.
 * - Threads within a group are ordered most-recent-first (by `updatedAt`, descending).
 * - Groups are ordered by their most-recently-updated thread, so an item with fresh activity
 *   floats to the top; "Other" sorts among the item groups by the same rule.
 * - Each group's [WorkItemThreadGroup.hasAttention] is rolled up from its threads.
 *
 * Pure + side-effect-free, so the ViewModel can call it and tests can exercise it directly.
 */
fun groupThreadsByWorkItem(
    threads: List<ThreadSummary>,
    items: List<WorkItemSummary>,
): List<WorkItemThreadGroup> {
    val itemsById = items.associateBy { it.id }
    // Group key: the owning item's id, or null (-> "Other") when the thread is unowned or its
    // work_item_id doesn't resolve to a known item. LinkedHashMap keeps first-seen order stable
    // for the tie-break in the final sort.
    val byKey = LinkedHashMap<String?, MutableList<ThreadSummary>>()
    for (t in threads) {
        val key = t.workItemId?.takeIf { itemsById.containsKey(it) }
        byKey.getOrPut(key) { mutableListOf() }.add(t)
    }
    return byKey.map { (key, groupThreads) ->
        val newestFirst = groupThreads.sortedByDescending { it.updatedAt ?: "" }
        val item = key?.let { itemsById[it] }
        WorkItemThreadGroup(
            workItemId = key,
            title = item?.title ?: OTHER_GROUP_TITLE,
            kind = item?.kind.orEmpty(),
            threads = newestFirst,
            hasAttention = newestFirst.any { it.needsAttention() },
        )
    }.sortedByDescending { group -> group.threads.firstOrNull()?.updatedAt ?: "" }
}
