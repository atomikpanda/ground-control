package com.atomikpanda.groundcontrol.ui.review

/**
 * The URL to open when a piece of acceptance-criterion evidence is tapped, or null when it
 * isn't tappable.
 *
 * - commit: opens the commit inside its PR — but only when the item has exactly one PR url
 *   (a bare commit SHA can't be attributed to a repo on multi-repo items): `<pr>/commits/<sha>`.
 * - artifact: opens the ref when it is an http(s) URL, else not tappable.
 * - test: never tappable (the ref is an internal test-run id).
 */
fun evidenceOpenUrl(kind: String, ref: String, prUrls: List<String>): String? = when (kind) {
    "commit" -> prUrls.singleOrNull()?.let { "${it.trimEnd('/')}/commits/$ref" }
    "artifact" -> ref.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    else -> null
}
