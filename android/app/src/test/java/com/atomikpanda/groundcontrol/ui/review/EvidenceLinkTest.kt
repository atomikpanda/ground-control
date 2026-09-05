package com.atomikpanda.groundcontrol.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceLinkTest {
    private val onePr = listOf("https://github.com/atomikpanda/mothership/pull/364")
    private val twoPrs = onePr + "https://github.com/atomikpanda/gc/pull/44"

    @Test fun commit_single_pr_opens_canonical_repository_commit() {
        assertEquals(
            "https://github.com/atomikpanda/mothership/commit/abc1234",
            evidenceOpenUrl("commit", "abc1234", onePr),
        )
    }

    @Test fun commit_trailing_slash_and_query_pr_is_normalized() {
        assertEquals(
            "https://github.com/atomikpanda/mothership/commit/abc1234",
            evidenceOpenUrl(
                "commit",
                "abc1234",
                listOf("https://github.com/atomikpanda/mothership/pull/364/?tab=commits"),
            ),
        )
    }

    @Test fun commit_multiple_prs_for_same_repository_is_attributable() {
        assertEquals(
            "https://github.com/atomikpanda/mothership/commit/abc1234",
            evidenceOpenUrl(
                "commit",
                "abc1234",
                onePr + "https://github.com/atomikpanda/mothership/pull/365",
            ),
        )
    }

    @Test fun commit_multi_repo_is_not_tappable() {
        assertNull(evidenceOpenUrl("commit", "abc1234", twoPrs))
    }

    @Test fun commit_no_pr_is_not_tappable() {
        assertNull(evidenceOpenUrl("commit", "abc1234", emptyList()))
    }

    @Test fun commit_rejects_non_sha_refs_and_malformed_pr_urls() {
        assertNull(evidenceOpenUrl("commit", "main", onePr))
        assertNull(evidenceOpenUrl("commit", "abc1234", listOf("https://github.com/atomikpanda/mothership/issues/364")))
    }

    @Test fun artifact_http_ref_opens_ref() {
        assertEquals("https://ci/run/9", evidenceOpenUrl("artifact", "https://ci/run/9", onePr))
    }

    @Test fun artifact_non_url_ref_is_not_tappable() {
        assertNull(evidenceOpenUrl("artifact", "/tmp/report.html", onePr))
    }

    @Test fun test_evidence_is_not_tappable() {
        assertNull(evidenceOpenUrl("test", "test-runs/3", onePr))
    }
}
