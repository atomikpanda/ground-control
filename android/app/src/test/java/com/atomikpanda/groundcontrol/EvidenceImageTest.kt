package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.dto.Evidence
import com.atomikpanda.groundcontrol.ui.specdetail.imageBlobPathOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
