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

/** Stored ref for an image artifact, or null for everything else. Encrypted image refs remain
 * eligible: the blob route decrypts them or reports that the workspace has no key. */
fun imageArtifactRefOrNull(e: Evidence): String? {
    if (e.kind != "artifact") return null
    val logical = e.ref.removeSuffix(ENC_SUFFIX)
    // The server's stored-ref shape requires a lowercase extension.
    val ext = logical.substringAfterLast('.', "")
    return e.ref.takeIf { ext in IMAGE_EXTS }
}

/** One image artifact to render inline. [ref] remains opaque here; SpecApi owns route construction
 * and authentication. [label] is the text fallback when loading or decoding fails. */
data class EvidenceImageRef(val ref: String, val note: String?, val label: String)

/** A criterion's evidence split for display: artifacts that render as pictures, and everything else
 * as today's compact text labels. */
data class EvidenceDisplay(val images: List<EvidenceImageRef>, val labels: List<String>)

/** Partition so an artifact shown as a picture never ALSO appears as an "artifact: <hash>.png"
 * line. Everything [imageArtifactRefOrNull] declines keeps its label. */
fun evidenceDisplay(evidence: List<Evidence>): EvidenceDisplay {
    val images = mutableListOf<EvidenceImageRef>()
    val rest = mutableListOf<Evidence>()
    evidence.forEach { e ->
        when (val ref = imageArtifactRefOrNull(e)) {
            null -> rest += e
            else -> images += EvidenceImageRef(ref, e.note, evidenceLabels(listOf(e)).first())
        }
    }
    return EvidenceDisplay(images, evidenceLabels(rest))
}
