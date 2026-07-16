package com.atomikpanda.groundcontrol.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceLinkTest {
    private val onePr = listOf("https://github.com/atomikpanda/mothership/pull/364")
    private val twoPrs = onePr + "https://github.com/atomikpanda/gc/pull/44"

    @Test fun commit_single_pr_builds_in_pr_commit_url() {
        assertEquals(
            "https://github.com/atomikpanda/mothership/pull/364/commits/abc123",
            evidenceOpenUrl("commit", "abc123", onePr),
        )
    }

    @Test fun commit_trailing_slash_pr_is_normalized() {
        assertEquals(
            "https://github.com/atomikpanda/mothership/pull/364/commits/abc123",
            evidenceOpenUrl("commit", "abc123", listOf("https://github.com/atomikpanda/mothership/pull/364/")),
        )
    }

    @Test fun commit_multi_pr_is_not_tappable() {
        assertNull(evidenceOpenUrl("commit", "abc123", twoPrs))
    }

    @Test fun commit_no_pr_is_not_tappable() {
        assertNull(evidenceOpenUrl("commit", "abc123", emptyList()))
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
