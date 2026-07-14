package com.atomikpanda.groundcontrol.data

/** Display groups for the inbox, in actionable-first order. */
enum class SpecGroup(val label: String) {
    NEEDS_REVIEW("Needs review"),
    READY_TO_DISPATCH("Ready to plan"),
    IN_IMPLEMENTATION("In implementation"),
    DRAFTING("Drafting"),
    DONE("Done"),
}

/** Map a raw mship spec status to its inbox group, or null to hide it. */
fun groupForStatus(status: String): SpecGroup? = when (status) {
    "needs_review", "needs_clarification" -> SpecGroup.NEEDS_REVIEW
    "approved" -> SpecGroup.READY_TO_DISPATCH
    "dispatched" -> SpecGroup.IN_IMPLEMENTATION
    "captured", "drafting" -> SpecGroup.DRAFTING
    "implemented" -> SpecGroup.DONE
    else -> null   // archived + anything unknown are excluded
}

/** Group display order. */
fun orderedGroups(): List<SpecGroup> = SpecGroup.entries.toList()
