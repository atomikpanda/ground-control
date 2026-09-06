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
import com.atomikpanda.groundcontrol.ui.specdetail.EvidenceImage
import com.atomikpanda.groundcontrol.ui.specdetail.EvidenceImageRef
import com.atomikpanda.groundcontrol.ui.specdetail.imageArtifactRefOrNull
import com.atomikpanda.groundcontrol.ui.specdetail.isUnverified
import com.atomikpanda.groundcontrol.ui.theme.LocalSemanticColors
import com.atomikpanda.groundcontrol.ui.theme.MonoStyle

private val commitSha = Regex("^[0-9a-fA-F]{7,64}$")
private val githubName = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")
private val pullNumber = Regex("^[1-9][0-9]*$")

/**
 * Resolves a commit evidence ref to GitHub's canonical repository commit route.
 *
 * A bare SHA is only attributable when every supplied PR URL is a well-formed GitHub pull-request
 * URL for the same repository. Query parameters and trailing path separators are ignored; fragments
 * and every other URL shape are rejected rather than creating a potentially misleading link.
 */
private fun commitEvidenceUrl(ref: String, prUrls: List<String>): String? {
    if (!commitSha.matches(ref)) return null

    var repository: String? = null
    for (prUrl in prUrls) {
        val candidate = repositoryForPullRequestUrl(prUrl) ?: return null
        if (repository == null) {
            repository = candidate
        } else if (repository != candidate) {
            return null
        }
    }
    return repository?.let { "$it/commit/$ref" }
}

private fun repositoryForPullRequestUrl(prUrl: String): String? {
    val uri = runCatching { java.net.URI(prUrl.trim()) }.getOrNull() ?: return null
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
        !uri.host.equals("github.com", ignoreCase = true) ||
        uri.port != -1 ||
        uri.userInfo != null ||
        uri.fragment != null
    ) {
        return null
    }

    val segments = uri.rawPath.orEmpty().trimEnd('/').split('/').filter(String::isNotEmpty)
    if (
        segments.size != 4 ||
        !githubName.matches(segments[0]) ||
        !githubName.matches(segments[1]) ||
        segments[2] != "pull" ||
        !pullNumber.matches(segments[3])
    ) {
        return null
    }
    return "https://github.com/${segments[0].lowercase()}/${segments[1].lowercase()}"
}

/**
 * The URL to open when a piece of acceptance-criterion evidence is tapped, or null when it
 * isn't tappable.
 *
 * - commit: opens its canonical GitHub repository commit URL when its SHA and repository
 *   attribution are unambiguous.
 * - artifact: opens the ref when it is an http(s) URL, else not tappable.
 * - test: never tappable (the ref is an internal test-run id).
 */
fun evidenceOpenUrl(kind: String, ref: String, prUrls: List<String>): String? = when (kind) {
    "commit" -> commitEvidenceUrl(ref, prUrls)
    "artifact" -> ref.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    else -> null
}

/**
 * Adds the "Acceptance criteria" header + one row per criterion (verdict + tappable evidence) to a
 * LazyColumn. Shared by the review page and the done/completion view so they can't drift. No-op when
 * [criteria] is empty (e.g. a no-spec item).
 */
fun LazyListScope.acceptanceCriteriaSection(
    criteria: List<ReviewCriterion>,
    prUrls: List<String>,
    loadEvidence: suspend (String) -> ByteArray,
) {
    if (criteria.isEmpty()) return
    item {
        Text(
            "Acceptance criteria",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(criteria, key = { it.id }) { crit -> CriterionEvidenceRow(crit, prUrls, loadEvidence) }
}

@Composable
private fun CriterionEvidenceRow(
    crit: ReviewCriterion,
    prUrls: List<String>,
    loadEvidence: suspend (String) -> ByteArray,
) {
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
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (isUnverified(crit.evidence)) {
            Text("unverified", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val labels = evidenceLabels(crit.evidence)
            crit.evidence.forEachIndexed { i, e ->
                val url = evidenceOpenUrl(e.kind, e.ref, prUrls)
                val base = Modifier.fillMaxWidth().padding(top = 2.dp)
                val imageRef = imageArtifactRefOrNull(e)
                if (imageRef != null && url == null) {
                    EvidenceImage(EvidenceImageRef(imageRef, e.note, labels[i]), loadEvidence)
                } else if (url != null) {
                    Text(
                        labels[i],
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = base.clickable { runCatching { uriHandler.openUri(url) } },
                    )
                } else {
                    Text(labels[i], style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = base)
                }
            }
        }
    }
}
