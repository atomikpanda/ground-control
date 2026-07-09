package com.atomikpanda.groundcontrol.ui.farm

import com.atomikpanda.groundcontrol.data.dto.WorkItemSummary

/** Lifecycle phases in pipeline order (Done last). `wire` matches the server's phase string. */
enum class FarmPhase(val wire: String, val label: String) {
    INBOX("inbox", "Inbox"),
    SHAPING("shaping", "Shaping"),
    READY("ready", "Ready"),
    IN_FLIGHT("in_flight", "In-flight"),
    REVIEW("review", "Review"),
    DONE("done", "Done");

    companion object {
        fun fromWire(s: String): FarmPhase? = entries.firstOrNull { it.wire == s }
    }
}

data class PhaseGroup(val phase: FarmPhase, val items: List<WorkItemSummary>)

/** The phase actually shown in the Farm list: a local `phaseOverride` (set by the "Mark done" /
 *  "Reopen" quick actions) wins over the server-derived `phase` when present. Grouping must key
 *  off this rather than the raw `phase` field, or a quick action's optimistic write is invisible
 *  until the next full refresh — the card just sits in its old group looking like the tap did
 *  nothing. */
fun WorkItemSummary.effectivePhase(): String = phaseOverride ?: phase

/** Bin items by (effective) phase in pipeline order; drop empty sections; newest-first within a
 *  section. Items whose phase the client doesn't recognize are omitted (server is source of
 *  truth). */
fun groupByPhase(items: List<WorkItemSummary>): List<PhaseGroup> =
    FarmPhase.entries.mapNotNull { phase ->
        items.filter { FarmPhase.fromWire(it.effectivePhase()) == phase }
            .sortedByDescending { it.updatedAt ?: "" }
            .takeIf { it.isNotEmpty() }
            ?.let { PhaseGroup(phase, it) }
    }
