package com.atomikpanda.groundcontrol.notify

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "notified", primaryKeys = ["connId", "threadId"])
data class NotifiedRecord(val connId: String, val threadId: String)

@Entity(tableName = "reply_actions")
data class ReplyActionRecord(
    @androidx.room.PrimaryKey val actionKey: String,
    val state: ReplyActionState,
    val executionId: String,
)

@Entity(tableName = "reply_notification_versions", primaryKeys = ["connId", "threadId"])
data class ReplyNotificationVersionRecord(
    val connId: String,
    val threadId: String,
    val sourceVersion: String,
    val generation: Long,
    val active: Boolean,
) {
    val version: String get() = "$sourceVersion#$generation"
}

@Dao
interface NotifiedDao {
    @Query("SELECT EXISTS(SELECT 1 FROM notified WHERE connId = :connId AND threadId = :threadId)")
    suspend fun isNotified(connId: String, threadId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: NotifiedRecord)

    @Query("DELETE FROM notified WHERE connId = :connId AND threadId = :threadId")
    suspend fun delete(connId: String, threadId: String)
}

@Dao
interface ReplyActionDao {
    @Query("SELECT * FROM reply_actions WHERE actionKey = :actionKey")
    suspend fun get(actionKey: String): ReplyActionRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ReplyActionRecord): Long

    @Query(
        "UPDATE reply_actions SET state = :inFlight, executionId = :executionId " +
            "WHERE actionKey = :actionKey AND state = :ready",
    )
    suspend fun reclaim(
        actionKey: String,
        ready: ReplyActionState,
        inFlight: ReplyActionState,
        executionId: String,
    ): Int

    @Query(
        "UPDATE reply_actions SET state = :next " +
            "WHERE actionKey = :actionKey AND state = :expected AND executionId = :executionId",
    )
    suspend fun transition(
        actionKey: String,
        expected: ReplyActionState,
        next: ReplyActionState,
        executionId: String,
    ): Int
}


@Dao
interface ReplyNotificationVersionDao {
    @Transaction
    suspend fun activate(
        connId: String,
        threadId: String,
        sourceVersion: String,
        forceNewGeneration: Boolean,
    ): ReplyNotificationVersionRecord {
        val current = get(connId, threadId)
        val next = if (
            !forceNewGeneration &&
            current != null &&
            current.active &&
            current.sourceVersion == sourceVersion
        ) {
            current
        } else {
            ReplyNotificationVersionRecord(
                connId = connId,
                threadId = threadId,
                sourceVersion = sourceVersion,
                generation = (current?.generation ?: 0) + 1,
                active = true,
            )
        }
        save(next)
        return next
    }
    @Query("SELECT * FROM reply_notification_versions WHERE connId = :connId AND threadId = :threadId")
    suspend fun get(connId: String, threadId: String): ReplyNotificationVersionRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: ReplyNotificationVersionRecord)

    @Query(
        "UPDATE reply_notification_versions SET active = 0 " +
            "WHERE connId = :connId AND threadId = :threadId AND generation = :generation AND active = 1",
    )
    suspend fun clearIfGeneration(connId: String, threadId: String, generation: Long): Int
}
@Database(
    entities = [NotifiedRecord::class, ReplyActionRecord::class, ReplyNotificationVersionRecord::class],
    version = 4,
    exportSchema = false,
)
abstract class NotifiedDatabase : RoomDatabase() {
    abstract fun notifiedDao(): NotifiedDao
    abstract fun replyActionDao(): ReplyActionDao
    abstract fun replyNotificationVersionDao(): ReplyNotificationVersionDao

    companion object {
        @Volatile private var instance: NotifiedDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reply_actions " +
                        "(actionKey TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(actionKey))",
                )
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reply_notification_versions " +
                        "(connId TEXT NOT NULL, threadId TEXT NOT NULL, sourceVersion TEXT NOT NULL, " +
                        "generation INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(connId, threadId))",
                )
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reply_actions ADD COLUMN executionId TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        fun get(context: Context): NotifiedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, NotifiedDatabase::class.java, "notified.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}

class RoomNotifiedStore(private val dao: NotifiedDao) : NotifiedStore {
    override suspend fun isNotified(connId: String, threadId: String) = dao.isNotified(connId, threadId)
    override suspend fun markNotified(connId: String, threadId: String) = dao.insert(NotifiedRecord(connId, threadId))
    override suspend fun clear(connId: String, threadId: String) = dao.delete(connId, threadId)
}

sealed interface ReplyActionStep {
    data object Post : ReplyActionStep
    data object RenderSafeFailure : ReplyActionStep
    data object RenderDelivered : ReplyActionStep
    data object RenderUncertain : ReplyActionStep
    data object WaitForOwner : ReplyActionStep
    data object Ignore : ReplyActionStep
}

class RoomReplyActionStore(private val dao: ReplyActionDao) {
    /** Claims only one WorkRequest execution. A restarted owner never re-POSTs an ambiguous in-flight
     * request; it reconciles through the uncertain render path instead. */
    suspend fun claim(actionKey: String, executionId: String): ReplyActionStep {
        val current = dao.get(actionKey)
        if (current == null) {
            if (dao.insert(ReplyActionRecord(actionKey, ReplyActionState.IN_FLIGHT, executionId)) != -1L) {
                return ReplyActionStep.Post
            }
            return claim(actionKey, executionId)
        }
        return when (current.state) {
            ReplyActionState.READY -> if (current.executionId == executionId) {
                ReplyActionStep.RenderSafeFailure
            } else if (
                dao.reclaim(actionKey, ReplyActionState.READY, ReplyActionState.IN_FLIGHT, executionId) == 1
            ) {
                ReplyActionStep.Post
            } else {
                claim(actionKey, executionId)
            }
            ReplyActionState.IN_FLIGHT -> if (current.executionId == executionId) {
                if (transition(actionKey, ReplyActionState.IN_FLIGHT, ReplyActionState.UNCERTAIN_PENDING_RENDER, executionId)) {
                    ReplyActionStep.RenderUncertain
                } else {
                    ReplyActionStep.Ignore
                }
            } else ReplyActionStep.Ignore
            ReplyActionState.SAFE_FAILURE_PENDING_RENDER ->
                if (current.executionId == executionId) ReplyActionStep.RenderSafeFailure
                else ReplyActionStep.WaitForOwner
            ReplyActionState.DELIVERED_PENDING_RENDER -> ReplyActionStep.RenderDelivered
            ReplyActionState.UNCERTAIN_PENDING_RENDER -> ReplyActionStep.RenderUncertain
            ReplyActionState.DELIVERED, ReplyActionState.UNCERTAIN -> ReplyActionStep.Ignore
        }
    }

    suspend fun transition(
        actionKey: String,
        expected: ReplyActionState,
        next: ReplyActionState,
        executionId: String,
    ): Boolean = dao.transition(actionKey, expected, next, executionId) == 1
}

class RoomReplyNotificationVersionStore(private val dao: ReplyNotificationVersionDao) {
    suspend fun activate(
        connId: String,
        threadId: String,
        sourceVersion: String,
        forceNewGeneration: Boolean = false,
    ): String = dao.activate(connId, threadId, sourceVersion, forceNewGeneration).version

    suspend fun isCurrent(connId: String, threadId: String, version: String): Boolean {
        val current = dao.get(connId, threadId) ?: return true // PendingIntent persisted before v3.
        return current.active && current.version == version
    }

    suspend fun activeGeneration(connId: String, threadId: String): Long? =
        dao.get(connId, threadId)?.takeIf { it.active }?.generation

    suspend fun clear(connId: String, threadId: String, expectedGeneration: Long?) {
        if (expectedGeneration != null) dao.clearIfGeneration(connId, threadId, expectedGeneration)
    }
}
