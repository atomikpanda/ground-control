// app/src/main/java/com/atomikpanda/groundcontrol/ui/specdetail/Evidence.kt
package com.atomikpanda.groundcontrol.ui.specdetail

import com.atomikpanda.groundcontrol.data.dto.Evidence

/** A criterion is unverified when nothing backs it — no evidence attached. The AC-evidence loop
 *  never *requires* evidence to approve, so this drives display (a muted "unverified" marker),
 *  not gating. Shared by the spec-detail criterion rows and the Queue criteria card. */
fun isUnverified(evidence: List<Evidence>): Boolean = evidence.isEmpty()

/** Compact one-line labels for a criterion's evidence: "kind: ref", plus " — note" when present.
 *  e.g. "test: pytest -q — 18 passed", "commit: abc123". */
fun evidenceLabels(evidence: List<Evidence>): List<String> =
    evidence.map { e ->
        buildString {
            append(e.kind)
            append(": ")
            append(e.ref)
            e.note?.takeIf { it.isNotBlank() }?.let { append(" — "); append(it) }
        }
    }
