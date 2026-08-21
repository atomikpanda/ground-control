package com.atomikpanda.groundcontrol.notify

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "notified", primaryKeys = ["connId", "threadId"])
data class NotifiedRecord(val connId: String, val threadId: String)

@Entity(tableName = "reply_outbox")
data class ReplyOutboxRecord(
    @androidx.room.PrimaryKey val actionKey: String,
    val connectionId: String,
    val threadId: String,
    val notificationVersion: String,
    val state: ReplyOutboxState,
    val executionId: String?,
    val claimedAtMillis: Long?,
    val renderVersion: String?,
    val renderCapabilityKey: String?,
    val replyText: String,
    val inputKind: ReplyInputKind,
    val subject: String,
    val workspace: String,
    val baseUrl: String,
    val decisionJson: String?,
    val retryAttempt: Int,
    val createdAtMillis: Long,
)

@Entity(tableName = "reply_action_tombstones")
data class ReplyActionTombstone(
    @androidx.room.PrimaryKey val actionKey: String,
    val terminalReason: String,
)

@Entity(tableName = "reply_notification_versions", primaryKeys = ["connId", "threadId"])
data class ReplyNotificationVersionRecord(
    val connId: String,
    val threadId: String,
    val version: String,
    val generation: Long,
    val active: Boolean,
    val capabilityKey: String?,
)

@Entity(tableName = "reply_migration_state")
data class ReplyMigrationState(
    @androidx.room.PrimaryKey val singletonId: Int = 0,
    val legacyNotificationResetRequired: Boolean,
)

class ReplyRoomConverters {
    @TypeConverter fun stateToString(value: ReplyOutboxState): String = value.name
    @TypeConverter fun stateFromString(value: String): ReplyOutboxState = ReplyOutboxState.valueOf(value)
    @TypeConverter fun inputKindToString(value: ReplyInputKind): String = value.name
    @TypeConverter fun inputKindFromString(value: String): ReplyInputKind = ReplyInputKind.valueOf(value)
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
interface ReplyOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ReplyOutboxRecord): Long

    @Query("SELECT * FROM reply_outbox WHERE actionKey = :actionKey")
    suspend fun get(actionKey: String): ReplyOutboxRecord?

    @Query("SELECT * FROM reply_outbox WHERE state = 'READY'")
    suspend fun ready(): List<ReplyOutboxRecord>

    @Query("SELECT * FROM reply_outbox WHERE state = 'WAITING_FOR_CONNECTION'")
    suspend fun waitingForConnection(): List<ReplyOutboxRecord>

    @Query("SELECT * FROM reply_outbox WHERE state IN ('SAFE_FAILURE_PENDING_RENDER', 'DELIVERED_PENDING_RENDER', 'UNCERTAIN_PENDING_RENDER')")
    suspend fun pendingRender(): List<ReplyOutboxRecord>

    @Query("UPDATE reply_outbox SET state = 'IN_FLIGHT', executionId = :executionId, claimedAtMillis = :claimedAtMillis WHERE actionKey = :actionKey AND notificationVersion = :version AND state = 'READY'")
    suspend fun claim(actionKey: String, version: String, executionId: String, claimedAtMillis: Long): Int

    @Query("UPDATE reply_outbox SET state = :next, executionId = NULL WHERE actionKey = :actionKey AND notificationVersion = :version AND state = 'IN_FLIGHT' AND executionId = :executionId")
    suspend fun completeClaim(actionKey: String, version: String, executionId: String, next: ReplyOutboxState): Int

    @Query("UPDATE reply_outbox SET state = :next WHERE actionKey = :actionKey AND notificationVersion = :version AND state = :expected")
    suspend fun transition(actionKey: String, version: String, expected: ReplyOutboxState, next: ReplyOutboxState): Int

    @Query("UPDATE reply_outbox SET state = 'READY' WHERE actionKey = :actionKey AND notificationVersion = :version AND state = 'WAITING_FOR_CONNECTION'")
    suspend fun resumeWaiting(actionKey: String, version: String): Int

    @Query("UPDATE reply_outbox SET state = :next WHERE actionKey = :actionKey AND notificationVersion = :version AND state = :expected AND renderVersion IS :renderVersion")
    suspend fun transitionForRender(actionKey: String, version: String, expected: ReplyOutboxState, renderVersion: String?, next: ReplyOutboxState): Int

    @Query("SELECT * FROM reply_outbox WHERE state = 'IN_FLIGHT'")
    suspend fun inFlight(): List<ReplyOutboxRecord>

    @Query("UPDATE reply_outbox SET state = 'UNCERTAIN_PENDING_RENDER', executionId = NULL WHERE actionKey = :actionKey AND state = 'IN_FLIGHT' AND executionId IS :executionId")
    suspend fun terminalizeAbandonedClaim(actionKey: String, executionId: String?): Int

    @Query("UPDATE reply_outbox SET state = 'STALE' WHERE actionKey = :actionKey AND state IN ('DELIVERED_PENDING_RENDER', 'SAFE_FAILURE_PENDING_RENDER', 'UNCERTAIN_PENDING_RENDER')")
    suspend fun markStale(actionKey: String): Int

    @Query(
        "UPDATE reply_outbox SET connectionId = :targetConnectionId " +
            "WHERE connectionId = :sourceConnectionId AND threadId = :threadId AND state IN " +
            "('READY', 'WAITING_FOR_CONNECTION', 'SAFE_FAILURE_PENDING_RENDER', " +
            "'DELIVERED_PENDING_RENDER', 'UNCERTAIN_PENDING_RENDER')",
    )
    suspend fun adoptConnection(sourceConnectionId: String, targetConnectionId: String, threadId: String): Int

    @Query(
        "UPDATE reply_outbox SET state = 'STALE' WHERE connectionId = :connectionId AND threadId = :threadId " +
            "AND state IN ('READY', 'WAITING_FOR_CONNECTION', 'SAFE_FAILURE_PENDING_RENDER', " +
            "'DELIVERED_PENDING_RENDER', 'UNCERTAIN_PENDING_RENDER')",
    )
    suspend fun terminalizeConnectionActions(connectionId: String, threadId: String): Int

    @Query("UPDATE reply_outbox SET renderVersion = :renderVersion, renderCapabilityKey = :capabilityKey WHERE actionKey = :actionKey AND state = 'SAFE_FAILURE_PENDING_RENDER'")
    suspend fun setRenderTarget(actionKey: String, renderVersion: String, capabilityKey: String): Int
}

@Dao
interface ReplyActionTombstoneDao {
    @Query("SELECT * FROM reply_action_tombstones WHERE actionKey = :actionKey")
    suspend fun get(actionKey: String): ReplyActionTombstone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ReplyActionTombstone)
}

@Dao
interface ReplyNotificationVersionDao {
    @Query("SELECT * FROM reply_notification_versions WHERE connId = :connectionId AND threadId = :threadId")
    suspend fun get(connectionId: String, threadId: String): ReplyNotificationVersionRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ReplyNotificationVersionRecord)

    @Query("UPDATE reply_notification_versions SET active = 0, capabilityKey = NULL WHERE connId = :connectionId AND threadId = :threadId AND version = :version")
    suspend fun deactivate(connectionId: String, threadId: String, version: String): Int
}

@Dao
interface ReplyMigrationStateDao {
    @Query("SELECT * FROM reply_migration_state WHERE singletonId = 0")
    suspend fun get(): ReplyMigrationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: ReplyMigrationState)
}

@Database(
    entities = [
        NotifiedRecord::class,
        ReplyOutboxRecord::class,
        ReplyActionTombstone::class,
        ReplyNotificationVersionRecord::class,
        ReplyMigrationState::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(ReplyRoomConverters::class)
abstract class NotifiedDatabase : RoomDatabase() {
    abstract fun notifiedDao(): NotifiedDao
    abstract fun replyOutboxDao(): ReplyOutboxDao
    abstract fun replyActionTombstoneDao(): ReplyActionTombstoneDao
    abstract fun replyNotificationVersionDao(): ReplyNotificationVersionDao
    abstract fun replyMigrationStateDao(): ReplyMigrationStateDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reply_actions (actionKey TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reply_notification_versions (connId TEXT NOT NULL, threadId TEXT NOT NULL, sourceVersion TEXT NOT NULL, generation INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(connId, threadId))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reply_actions ADD COLUMN executionId TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reply_outbox (actionKey TEXT NOT NULL PRIMARY KEY, connectionId TEXT NOT NULL, threadId TEXT NOT NULL, notificationVersion TEXT NOT NULL, state TEXT NOT NULL, executionId TEXT, claimedAtMillis INTEGER, renderVersion TEXT, renderCapabilityKey TEXT, replyText TEXT NOT NULL, inputKind TEXT NOT NULL, subject TEXT NOT NULL, workspace TEXT NOT NULL, baseUrl TEXT NOT NULL, decisionJson TEXT, retryAttempt INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reply_action_tombstones (actionKey TEXT NOT NULL PRIMARY KEY, terminalReason TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reply_migration_state (singletonId INTEGER NOT NULL PRIMARY KEY, legacyNotificationResetRequired INTEGER NOT NULL)")
                db.execSQL("INSERT OR REPLACE INTO reply_action_tombstones(actionKey, terminalReason) SELECT actionKey, CASE state WHEN 'DELIVERED' THEN 'DELIVERED' WHEN 'DELIVERED_PENDING_RENDER' THEN 'DELIVERED' WHEN 'UNCERTAIN' THEN 'UNCERTAIN' WHEN 'UNCERTAIN_PENDING_RENDER' THEN 'UNCERTAIN' WHEN 'IN_FLIGHT' THEN 'UNCERTAIN' ELSE 'LEGACY_UNEXECUTABLE' END FROM reply_actions")
                db.execSQL("DELETE FROM notified")
                db.execSQL("ALTER TABLE reply_notification_versions RENAME TO reply_notification_versions_legacy")
                db.execSQL("CREATE TABLE reply_notification_versions (connId TEXT NOT NULL, threadId TEXT NOT NULL, version TEXT NOT NULL, generation INTEGER NOT NULL, active INTEGER NOT NULL, capabilityKey TEXT, PRIMARY KEY(connId, threadId))")
                db.execSQL("INSERT INTO reply_notification_versions(connId, threadId, version, generation, active, capabilityKey) SELECT connId, threadId, sourceVersion, generation, 0, NULL FROM reply_notification_versions_legacy")
                db.execSQL("DROP TABLE reply_notification_versions_legacy")
                db.execSQL("INSERT OR REPLACE INTO reply_migration_state(singletonId, legacyNotificationResetRequired) VALUES(0, 1)")
            }
        }
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        @Volatile private var instance: NotifiedDatabase? = null
        fun get(context: Context): NotifiedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, NotifiedDatabase::class.java, "notified.db",
            ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
        }
    }
}

class RoomNotifiedStore(private val dao: NotifiedDao) : NotifiedStore {
    override suspend fun isNotified(connId: String, threadId: String) = dao.isNotified(connId, threadId)
    override suspend fun markNotified(connId: String, threadId: String) = dao.insert(NotifiedRecord(connId, threadId))
    override suspend fun clear(connId: String, threadId: String) = dao.delete(connId, threadId)
}
