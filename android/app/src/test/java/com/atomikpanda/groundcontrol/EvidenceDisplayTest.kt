package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Evidence
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceLabels
import com.atomikpanda.groundcontrol.ui.specdetail.isUnverified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared evidence-display helpers used by both the spec-detail criterion rows and the Queue
 *  criteria card: unverified = no evidence, and each entry renders as a compact "kind: ref [— note]". */
class EvidenceDisplayTest {
    @Test fun empty_evidence_is_unverified() {
        assertTrue(isUnverified(emptyList()))
        assertFalse(isUnverified(listOf(Evidence("artifact", "build.apk"))))
    }

    @Test fun labels_render_kind_ref_and_optional_note() {
        val labels = evidenceLabels(
            listOf(
                Evidence("test", "pytest -q", "18 passed"),
                Evidence("commit", "abc123"),
                Evidence("artifact", "build.apk", "   "),  // blank note omitted
            )
        )
        assertEquals(
            listOf("test: pytest -q — 18 passed", "commit: abc123", "artifact: build.apk"),
            labels,
        )
    }
}
