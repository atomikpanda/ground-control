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

    @Test fun reply_worker_waits_for_migration_reset_before_reading_its_input() = runTest {
        ReplyStartupGate.beginReset()
        val worker = async {
            ReplyWorker.awaitStartupGate()
            true
        }

        assertFalse(worker.isCompleted)
        ReplyStartupGate.finishReset()
        assertTrue(worker.await())
    }

    @Test fun reset_failure_keeps_waiters_closed_until_a_later_successful_reset() = runTest {
        ReplyStartupGate.beginReset()
        val watcher = async {
            ReplyStartupGate.awaitReset()
            true
        }

        runCatching {
            withReplyStartupGate<Nothing> { error("database open failed") }
        }

        assertFalse(watcher.isCompleted)
        withReplyStartupGate { Unit }
        assertTrue(watcher.await())
    }
}
