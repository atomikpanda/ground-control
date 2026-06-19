package com.atomikpanda.groundcontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the `capture` go-task target (image+layout) against drift. No device. */
class CaptureTaskfileTest {

    private fun findTaskfile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "Taskfile.yml")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError(
            "no Taskfile.yml found upward from ${System.getProperty("user.dir")}"
        )
    }

    @Test
    fun capture_target_has_expected_commands() {
        val text = findTaskfile().readText()
        assertTrue("has a capture target", text.contains("capture:"))
        assertTrue("writes to MSHIP_CAPTURE_DIR", text.contains("MSHIP_CAPTURE_DIR"))
        assertTrue("android screenshot via screencap", text.contains("screencap"))
        assertTrue("android layout via uiautomator dump", text.contains("uiautomator dump"))
        assertTrue("ios screenshot via simctl", text.contains("simctl io booted screenshot"))
    }
}
