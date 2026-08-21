package com.atomikpanda.groundcontrol.notify

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotifiedDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "notified-migration-test.db"

    @After fun deleteDatabase() { context.deleteDatabase(name) }

    @Test fun migration_4_5_tombstones_every_legacy_action_without_inventing_payload() = runBlocking {
        createLegacyDatabase(4)
        val room = openVersionFive()
        assertEquals("DELIVERED", room.replyActionTombstoneDao().get("done")!!.terminalReason)
        assertEquals("UNCERTAIN", room.replyActionTombstoneDao().get("uncertain")!!.terminalReason)
        assertEquals("UNCERTAIN", room.replyActionTombstoneDao().get("flight")!!.terminalReason)
        assertEquals("LEGACY_UNEXECUTABLE", room.replyActionTombstoneDao().get("pending")!!.terminalReason)
        assertEquals("LEGACY_UNEXECUTABLE", room.replyActionTombstoneDao().get("safe")!!.terminalReason)
        listOf("done", "uncertain", "flight", "pending", "safe").forEach { assertNull(room.replyOutboxDao().get(it)) }
        assertFalse(room.replyNotificationVersionDao().get("c", "t")!!.active)
        assertNull(room.replyNotificationVersionDao().get("c", "t")!!.capabilityKey)
        assertTrue(room.replyMigrationStateDao().get()!!.legacyNotificationResetRequired)
        room.close()
    }

    @Test fun every_supported_legacy_version_opens_at_version_five() = runBlocking {
        (1..4).forEach { version ->
            context.deleteDatabase(name)
            createLegacyDatabase(version)
            openVersionFive().close()
        }
    }

    @Test fun unsupported_nonempty_version_fails_open_instead_of_resetting() {
        createLegacyDatabase(6)
        assertTrue(runCatching { openVersionFive().close() }.isFailure)
    }

    @Test fun fresh_version_five_does_not_require_legacy_notification_reset() = runBlocking {
        val room = openVersionFive()
        assertNull(room.replyMigrationStateDao().get())
        room.close()
    }

    @Test fun legacy_notification_reset_repeats_after_crash_before_marker_acknowledgement() = runBlocking {
        val room = openVersionFive()
        room.replyMigrationStateDao().insert(ReplyMigrationState(legacyNotificationResetRequired = true))
        var calls = 0
        val resetter = ReplyMigrationResetter(room) {
            calls++
            if (calls == 1) throw IllegalStateException("interrupted cancellation")
        }
        runCatching { resetter.resetIfRequired() }
        assertTrue(room.replyMigrationStateDao().get()!!.legacyNotificationResetRequired)
        resetter.resetIfRequired()
        assertEquals(2, calls)
        assertFalse(room.replyMigrationStateDao().get()!!.legacyNotificationResetRequired)
        room.close()
    }

    private fun openVersionFive(): NotifiedDatabase = Room.databaseBuilder(context, NotifiedDatabase::class.java, name)
        .addMigrations(*NotifiedDatabase.ALL_MIGRATIONS)
        .build()

    private fun createLegacyDatabase(version: Int) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE notified (connId TEXT NOT NULL, threadId TEXT NOT NULL, PRIMARY KEY(connId, threadId))")
                        if (version >= 2) db.execSQL("CREATE TABLE reply_actions (actionKey TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL${if (version >= 4) ", executionId TEXT NOT NULL DEFAULT ''" else ""})")
                        if (version >= 3) db.execSQL("CREATE TABLE reply_notification_versions (connId TEXT NOT NULL, threadId TEXT NOT NULL, sourceVersion TEXT NOT NULL, generation INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(connId, threadId))")
                        if (version == 4) {
                            db.execSQL("INSERT INTO reply_actions(actionKey,state,executionId) VALUES('done','DELIVERED','')")
                            db.execSQL("INSERT INTO reply_actions(actionKey,state,executionId) VALUES('uncertain','UNCERTAIN_PENDING_RENDER','')")
                            db.execSQL("INSERT INTO reply_actions(actionKey,state,executionId) VALUES('flight','IN_FLIGHT','owner')")
                            db.execSQL("INSERT INTO reply_actions(actionKey,state,executionId) VALUES('pending','READY','')")
                            db.execSQL("INSERT INTO reply_actions(actionKey,state,executionId) VALUES('safe','SAFE_FAILURE_PENDING_RENDER','')")
                            db.execSQL("INSERT INTO reply_notification_versions(connId,threadId,sourceVersion,generation,active) VALUES('c','t','source',7,1)")
                        }
                        if (version == 6) db.execSQL("INSERT INTO notified(connId,threadId) VALUES('unsupported','thread')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }
}
