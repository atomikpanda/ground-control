package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.notify.ReplyStartupGate
import com.atomikpanda.groundcontrol.notify.withReplyStartupGate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyStartupGateTest {
    @Test fun watcher_started_before_reset_coroutine_cannot_pass_closed_generation() = runTest {
        ReplyStartupGate.beginReset()
        val watcher = async {
            ReplyStartupGate.awaitReset()
            true
        }
        assertFalse(watcher.isCompleted)
        ReplyStartupGate.finishReset()
        assertTrue(watcher.await())
    }

    @Test fun database_open_failure_releases_waiters_from_the_reset_generation() = runTest {
        ReplyStartupGate.beginReset()
        val watcher = async {
            ReplyStartupGate.awaitReset()
            true
        }

        runCatching {
            withReplyStartupGate<Nothing> { error("database open failed") }
        }

        assertTrue(watcher.await())
    }
}
