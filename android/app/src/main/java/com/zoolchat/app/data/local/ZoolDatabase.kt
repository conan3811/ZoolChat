package com.zoolchat.app.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_chats")
data class CachedChatEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val peerUserId: String?,
    val lastMessage: String?,
    val lastMessageAt: String?,
    val unread: Int,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "cached_messages",
    indices = [Index("chatId"), Index(value = ["clientId"], unique = false)],
)
data class CachedMessageEntity(
    @PrimaryKey val id: String,
    val clientId: String?,
    val chatId: String,
    val senderId: String,
    val type: String,
    val body: String,
    val createdAt: String,
    val status: String,
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
    val mediaSize: Long? = null,
    val encrypted: Boolean = false,
    val cryptoVersion: Int = 0,
    val decryptionFailed: Boolean = false,
)

@Entity(tableName = "pending_messages", indices = [Index("chatId")])
data class PendingMessageEntity(
    @PrimaryKey val clientId: String,
    val chatId: String,
    val senderId: String,
    val type: String,
    val body: String,
    val localUri: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
    val createdAt: String,
    val attempts: Int = 0,
)

@Dao
interface ChatDao {
    @Query("UPDATE cached_chats SET unread=0 WHERE id=:chatId")
    suspend fun clearUnread(chatId: String)

    @Query("SELECT * FROM cached_chats ORDER BY CASE WHEN lastMessageAt IS NULL THEN 1 ELSE 0 END, lastMessageAt DESC, updatedAtMs DESC")
    fun observeAll(): Flow<List<CachedChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedChatEntity>)

    @Query("DELETE FROM cached_chats WHERE id NOT IN (:ids)")
    suspend fun deleteMissing(ids: List<String>)

    @Query("DELETE FROM cached_chats")
    suspend fun clearAll()

    @Query("SELECT * FROM cached_chats WHERE id=:chatId LIMIT 1")
    suspend fun byId(chatId: String): CachedChatEntity?

    @Query("UPDATE cached_chats SET lastMessage=:message,lastMessageAt=:at,updatedAtMs=:updated WHERE id=:chatId")
    suspend fun updateLastMessage(chatId: String, message: String, at: String, updated: Long = System.currentTimeMillis())
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE chatId=:chatId ORDER BY createdAt ASC")
    fun observeChat(chatId: String): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CachedMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE id=:id")
    suspend fun deleteById(id: String)

    @Query("UPDATE cached_messages SET status=:status WHERE id=:messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE cached_messages SET status=:status WHERE clientId=:clientId")
    suspend fun updateStatusByClientId(clientId: String, status: String)

    @Query("UPDATE cached_messages SET body=:body WHERE id=:messageId")
    suspend fun updateBody(messageId: String, body: String)

    @Query("UPDATE cached_messages SET body='', deleted=1 WHERE id=:messageId")
    suspend fun markDeleted(messageId: String)

    @Query("SELECT * FROM cached_messages WHERE id=:messageId LIMIT 1")
    suspend fun byId(messageId: String): CachedMessageEntity?

    @Query("SELECT * FROM cached_messages WHERE chatId=:chatId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForChat(chatId: String): CachedMessageEntity?

    @Query("SELECT MIN(createdAt) FROM cached_messages WHERE chatId=:chatId AND id NOT LIKE 'local:%'")
    suspend fun oldestCreatedAt(chatId: String): String?

    @Query("DELETE FROM cached_messages")
    suspend fun clearAll()
}

@Dao
interface PendingMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PendingMessageEntity)

    @Query("SELECT * FROM pending_messages WHERE attempts < 5 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun next(limit: Int = 20): List<PendingMessageEntity>

    @Query("SELECT * FROM pending_messages WHERE clientId=:clientId LIMIT 1")
    suspend fun byClientId(clientId: String): PendingMessageEntity?

    @Query("DELETE FROM pending_messages WHERE clientId=:clientId")
    suspend fun delete(clientId: String)

    @Query("UPDATE pending_messages SET attempts=attempts+1 WHERE clientId=:clientId")
    suspend fun incrementAttempts(clientId: String)

    @Query("UPDATE pending_messages SET attempts=0 WHERE clientId=:clientId")
    suspend fun resetAttempts(clientId: String)

    @Query("DELETE FROM pending_messages")
    suspend fun clearAll()
}

@Database(
    entities = [CachedChatEntity::class, CachedMessageEntity::class, PendingMessageEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ZoolDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun messages(): MessageDao
    abstract fun pending(): PendingMessageDao

    companion object {
        @Volatile private var INSTANCE: ZoolDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN editedAt TEXT")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN cryptoVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN decryptionFailed INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun get(context: Context): ZoolDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ZoolDatabase::class.java,
                "zoolchat_cache.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }
    }
}
