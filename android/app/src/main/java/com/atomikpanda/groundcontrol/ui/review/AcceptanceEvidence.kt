package com.atomikpanda.groundcontrol.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.atomikpanda.groundcontrol.data.dto.ReviewCriterion
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceLabels
import com.atomikpanda.groundcontrol.ui.specdetail.isUnverified
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

/**
 * The URL to open when a piece of acceptance-criterion evidence is tapped, or null when it
 * isn't tappable.
 *
 * - commit: opens the commit inside its PR — but only when the item has exactly one PR url
 *   (a bare commit SHA can't be attributed to a repo on multi-repo items): `<pr>/commits/<sha>`.
 *   Still valid on a merged PR (GitHub keeps a merged PR's commits).
 * - artifact: opens the ref when it is an http(s) URL, else not tappable.
 * - test: never tappable (the ref is an internal test-run id).
 */
fun evidenceOpenUrl(kind: String, ref: String, prUrls: List<String>): String? = when (kind) {
    "commit" -> prUrls.singleOrNull()?.let { "${it.trimEnd('/')}/commits/$ref" }
    "artifact" -> ref.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    else -> null
}

/**
 * Adds the "Acceptance criteria" header + one row per criterion (verdict + tappable evidence) to a
 * LazyColumn. Shared by the review page and the done/completion view so they can't drift. No-op when
 * [criteria] is empty (e.g. a no-spec item).
 */
fun LazyListScope.acceptanceCriteriaSection(criteria: List<ReviewCriterion>, prUrls: List<String>) {
    if (criteria.isEmpty()) return
    item {
        Text(
            "Acceptance criteria",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(criteria, key = { it.id }) { crit -> CriterionEvidenceRow(crit, prUrls) }
}

@Composable
private fun CriterionEvidenceRow(crit: ReviewCriterion, prUrls: List<String>) {
    val colors = LocalSemanticColors.current
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(crit.text, style = MaterialTheme.typography.bodyMedium)
        // Show the review verdict — a flagged criterion must not read as approved.
        Text(
            crit.verdict,
            style = MonoStyle,
            color = when (crit.verdict) {
                "approved" -> colors.approval
                "flagged" -> colors.error
                else -> colors.muted
            },
        )
        if (isUnverified(crit.evidence)) {
            Text("unverified", style = MonoStyle, color = colors.muted)
        } else {
            val labels = evidenceLabels(crit.evidence)
            crit.evidence.forEachIndexed { i, e ->
                val url = evidenceOpenUrl(e.kind, e.ref, prUrls)
                val base = Modifier.fillMaxWidth().padding(top = 2.dp)
                if (url != null) {
                    Text(
                        labels[i],
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = base.clickable { runCatching { uriHandler.openUri(url) } },
                    )
                } else {
                    Text(labels[i], style = MonoStyle, color = colors.muted, modifier = base)
                }
            }
        }
    }
}
