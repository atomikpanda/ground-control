package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Evidence
import com.atomikpanda.groundcontrol.ui.specdetail.evidenceDisplay
import com.atomikpanda.groundcontrol.ui.specdetail.imageBlobPathOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceImageTest {
    private fun ev(kind: String, ref: String) = Evidence(kind = kind, ref = ref, note = null)

    @Test
    fun `image artifact yields a blob path`() {
        assertEquals(
            "/specs/my-spec/evidence/a1b2c3d4e5f6.png/blob",
            imageBlobPathOrNull(ev("artifact", "a1b2c3d4e5f6.png"), "my-spec"),
        )
    }

    @Test
    fun `non-image artifact yields null`() {
        assertNull(imageBlobPathOrNull(ev("artifact", "a1b2c3d4e5f6.xml"), "my-spec"))
    }

    @Test
    fun `test and commit evidence yield null`() {
        assertNull(imageBlobPathOrNull(ev("test", "test-runs/7"), "my-spec"))
        assertNull(imageBlobPathOrNull(ev("commit", "abc123"), "my-spec"))
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals(
            "/specs/s/evidence/a1b2c3d4e5f6.PNG/blob",
            imageBlobPathOrNull(ev("artifact", "a1b2c3d4e5f6.PNG"), "s"),
        )
    }

    // serve's blob route decrypts `.enc` refs transparently when the host holds
    // the key, returning real image bytes — or a 409 "locked" otherwise. Either
    // way the path is the same shape, so the phone always tries; Task 14 treats
    // a 409 as a locked state rather than this helper pre-filtering it out.
    @Test
    fun `encrypted image artifact still yields a blob path`() {
        assertEquals(
            "/specs/s/evidence/a1b2c3d4e5f6.png.enc/blob",
            imageBlobPathOrNull(ev("artifact", "a1b2c3d4e5f6.png.enc"), "s"),
        )
    }

    @Test
    fun `ref with no extension yields null`() {
        assertNull(imageBlobPathOrNull(ev("artifact", "a1b2c3d4e5f6"), "s"))
    }

    // --- display partition (Task 14): images render as pictures, the rest keep their text labels ---

    @Test
    fun `an image artifact renders as a picture and NOT also as a text label`() {
        val d = evidenceDisplay(
            listOf(Evidence("artifact", "a1b2c3d4e5f6.png", "screenshot (android) @ abc123")),
            "my-spec",
            "http://host:8765",
        )
        assertEquals(1, d.images.size)
        assertEquals("http://host:8765/specs/my-spec/evidence/a1b2c3d4e5f6.png/blob", d.images[0].url)
        assertEquals("screenshot (android) @ abc123", d.images[0].note)
        assertTrue(d.labels.isEmpty())
    }

    @Test
    fun `non-image evidence keeps its label and yields no image`() {
        val d = evidenceDisplay(
            listOf(Evidence("test", "pytest -q", "18 passed"), Evidence("commit", "abc123")),
            "s",
            "http://host:8765",
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
            "s",
            "http://h",
        )
        assertEquals(listOf("http://h/specs/s/evidence/aaa.png/blob", "http://h/specs/s/evidence/bbb.jpg/blob"), d.images.map { it.url })
        assertEquals(listOf("test: pytest -q", "artifact: notes.xml"), d.labels)
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        val d = evidenceDisplay(listOf(Evidence("artifact", "aaa.png")), "s", "http://h/")
        assertEquals("http://h/specs/s/evidence/aaa.png/blob", d.images[0].url)
    }

    /** A failed load degrades to the line this evidence would have shown, so the fallback text has
     *  to travel with the image ref. */
    @Test
    fun `image ref carries its text label as the load-failure fallback`() {
        val d = evidenceDisplay(listOf(Evidence("artifact", "aaa.png", "shot")), "s", "http://h")
        assertEquals("artifact: aaa.png — shot", d.images[0].label)
    }
}
