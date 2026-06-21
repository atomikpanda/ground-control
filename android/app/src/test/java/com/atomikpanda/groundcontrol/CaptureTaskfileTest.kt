package com.atomikpanda.groundcontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the `capture` go-task target (image+layout) against drift. No device. */
class CaptureTaskfileTest {

    private fun findTaskfile(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val start = File(cwd).absoluteFile
        var dir: File? = start
        while (dir != null) {
            val f = File(dir, "Taskfile.yml")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("no Taskfile.yml found upward from $start")
    }

    @Test
    fun capture_target_has_expected_commands() {
        val text = findTaskfile().readText()
        // Anchor to the task declaration (2-space indent under `tasks:`), not any
        // incidental occurrence of "capture:" in a comment or description.
        assertTrue("declares a capture task", text.contains("\n  capture:\n"))
        assertTrue("writes to MSHIP_CAPTURE_DIR", text.contains("MSHIP_CAPTURE_DIR"))
        assertTrue("android screenshot via screencap", text.contains("screencap"))
        assertTrue("android layout via uiautomator dump", text.contains("uiautomator dump"))
        assertTrue("ios screenshot via simctl", text.contains("simctl io booted screenshot"))
    }

    @Test
    fun capture_guards_missing_output_dir() {
        val text = findTaskfile().readText()
        // A direct `task capture` without MSHIP_CAPTURE_DIR fails clearly rather
        // than expanding to a stray `/screen.png` path.
        assertTrue(
            "guards unset MSHIP_CAPTURE_DIR",
            text.contains("test -n \"\$MSHIP_CAPTURE_DIR\""),
        )
    }

    @Test
    fun capture_layout_step_is_best_effort() {
        val text = findTaskfile().readText()
        // The android layout dump must not poison the task exit code when the
        // screenshot already succeeded.
        assertTrue("layout dump is best-effort", text.contains("|| true"))
    }
}
