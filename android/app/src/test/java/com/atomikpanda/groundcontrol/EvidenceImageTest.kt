package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Evidence
import com.atomikpanda.groundcontrol.data.EvidenceLockedException
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceDisplay
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceLoadFailureText
import com.atomikpanda.groundcontrol.ui.specdetail.imageArtifactRefOrNull
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceImageTest {
    private fun ev(kind: String, ref: String) = Evidence(kind = kind, ref = ref, note = null)

    @Test
    fun `image artifact yields its opaque ref`() {
        assertEquals(
            "a1b2c3d4e5f6.png",
            imageArtifactRefOrNull(ev("artifact", "a1b2c3d4e5f6.png")),
        )
    }

    @Test
    fun `non-image artifact yields null`() {
        assertNull(imageArtifactRefOrNull(ev("artifact", "a1b2c3d4e5f6.xml")))
    }

    @Test
    fun `test and commit evidence yield null`() {
        assertNull(imageArtifactRefOrNull(ev("test", "test-runs/7")))
        assertNull(imageArtifactRefOrNull(ev("commit", "abc123")))
    }

    // The server's ref shape (`evidence_store.py::is_stored_ref` / `_REF_RE`) requires
    // a lowercase extension — `store_artifact` never produces anything else, and the
    // blob route 404s on a mismatch. Matching that case-sensitively here means the
    // phone never builds a blob URL the server is guaranteed to reject.
    @Test
    fun `uppercase extension yields null (server ref shape is lowercase-only)`() {
        assertNull(imageArtifactRefOrNull(ev("artifact", "a1b2c3d4e5f6.PNG")))
    }

    // serve's blob route decrypts `.enc` refs transparently when the host holds
    // the key, returning real image bytes — or a 409 "locked" otherwise. Either
    // way the path is the same shape, so the phone always tries; Task 14 treats
    // a 409 as a locked state rather than this helper pre-filtering it out.
    @Test
    fun `encrypted image artifact still yields its opaque ref`() {
        assertEquals(
            "a1b2c3d4e5f6.png.enc",
            imageArtifactRefOrNull(ev("artifact", "a1b2c3d4e5f6.png.enc")),
        )
    }

    @Test
    fun `ref with no extension yields null`() {
        assertNull(imageArtifactRefOrNull(ev("artifact", "a1b2c3d4e5f6")))
    }

    // --- display partition (Task 14): images render as pictures, the rest keep their text labels ---

    @Test
    fun `an image artifact renders as a picture and NOT also as a text label`() {
        val d = evidenceDisplay(
            listOf(Evidence("artifact", "a1b2c3d4e5f6.png", "screenshot (android) @ abc123")),
        )
        assertEquals(1, d.images.size)
        assertEquals("a1b2c3d4e5f6.png", d.images[0].ref)
        assertEquals("screenshot (android) @ abc123", d.images[0].note)
        assertTrue(d.labels.isEmpty())
    }

    @Test
    fun `non-image evidence keeps its label and yields no image`() {
        val d = evidenceDisplay(
            listOf(Evidence("test", "pytest -q", "18 passed"), Evidence("commit", "abc123")),
        )
        assertTrue(d.images.isEmpty())
        assertEquals(listOf("test: pytest -q — 18 passed", "commit: abc123"), d.labels)
    }

    @Test
    fun `mixed evidence splits, preserving order within each side`() {
        val d = evidenceDisplay(
            listOf(
                Evidence("test", "pytest -q"),
                Evidence("artifact", "aaa.png"),
                Evidence("artifact", "notes.xml"),
                Evidence("artifact", "bbb.jpg"),
            ),
        )
        assertEquals(listOf("aaa.png", "bbb.jpg"), d.images.map { it.ref })
        assertEquals(listOf("test: pytest -q", "artifact: notes.xml"), d.labels)
    }


    /** A failed load degrades to the line this evidence would have shown, so the fallback text has
     *  to travel with the image ref. */
    @Test
    fun `image ref carries its text label as the load-failure fallback`() {
        val d = evidenceDisplay(listOf(Evidence("artifact", "aaa.png", "shot")))
        assertEquals("artifact: aaa.png — shot", d.images[0].label)
    }

    // --- EvidenceImage.kt's error-slot TEXT (not the Compose wiring — see note below) ---


    @Test
    fun `a locked evidence failure renders the locked message`() {
        assertEquals(
            "🔒 locked — no key for this artifact on this workspace",
            evidenceLoadFailureText(EvidenceLockedException(), "artifact: aaa.png"),
        )
    }

    // Every other failure falls back to the evidence's own text label.
    @Test
    fun `an ordinary load failure falls back to the evidence label`() {
        val label = "artifact: aaa.png"
        assertEquals(label, evidenceLoadFailureText(IOException("boom"), label))
        assertEquals(label, evidenceLoadFailureText(null, label))
    }

    // NOTE on what this file does NOT prove: `evidenceLoadFailureText` covers the locked-vs-label
    // TEXT decision, but `EvidenceImage.kt`'s `EvidenceLoadFailure` composable is wired into
    // `SubcomposeAsyncImage`'s `error = { … }` slot — a regression that dropped that slot entirely
    // (leaving Coil's default broken-image behaviour) would NOT be caught by any test here or
    // elsewhere in this module. Proving the slot itself stays wired needs a Compose UI test
    // (`createComposeRule` + a semantics assertion), and this project has no JVM-runnable rig for
    // that (no Robolectric, no `androidx.compose.ui:ui-test-junit4` test dependency) — only
    // instrumented `androidTest`, which needs a device/emulator we don't have here.
}
