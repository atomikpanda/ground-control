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

@Entity(tableName = "notified", primaryKeys = ["connId", "threadId"])
data class NotifiedRecord(val connId: String, val threadId: String)

@Dao
interface NotifiedDao {
    @Query("SELECT EXISTS(SELECT 1 FROM notified WHERE connId = :connId AND threadId = :threadId)")
    suspend fun isNotified(connId: String, threadId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: NotifiedRecord)

    @Query("DELETE FROM notified WHERE connId = :connId AND threadId = :threadId")
    suspend fun delete(connId: String, threadId: String)
}

@Database(entities = [NotifiedRecord::class], version = 1, exportSchema = false)
abstract class NotifiedDatabase : RoomDatabase() {
    abstract fun notifiedDao(): NotifiedDao

    companion object {
        @Volatile private var instance: NotifiedDatabase? = null
        fun get(context: Context): NotifiedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, NotifiedDatabase::class.java, "notified.db"
            ).build().also { instance = it }
        }
    }
}

class RoomNotifiedStore(private val dao: NotifiedDao) : NotifiedStore {
    override suspend fun isNotified(connId: String, threadId: String) = dao.isNotified(connId, threadId)
    override suspend fun markNotified(connId: String, threadId: String) = dao.insert(NotifiedRecord(connId, threadId))
    override suspend fun clear(connId: String, threadId: String) = dao.delete(connId, threadId)
}
