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

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")
private const val ENC_SUFFIX = ".enc"

/** Blob path for an image artifact, or null for everything else (non-image artifacts, and
 *  `test`/`commit` refs, which keep the existing text label from [evidenceLabels]).
 *
 *  An encrypted ref (`…enc`) still yields a path: serve's blob route decrypts it transparently
 *  when the host holds the key, returning real image bytes with the right content-type — it 409s
 *  "locked" otherwise. So the phone always tries; it can't know up front whether this host can
 *  unlock it, and that 409 is a fetch failure for the caller (Task 14) to handle, not something
 *  this helper pre-filters.
 *
 *  A ref is a bare content-hash filename (`store_artifact` in evidence_store.py), never a path, so
 *  it has no separators to escape — always safe to interpolate directly into the URL. */
fun imageBlobPathOrNull(e: Evidence, specId: String): String? {
    if (e.kind != "artifact") return null
    val logical = e.ref.removeSuffix(ENC_SUFFIX)
    val ext = logical.substringAfterLast('.', "").lowercase()
    if (ext !in IMAGE_EXTS) return null
    return "/specs/$specId/evidence/${e.ref}/blob"
}
