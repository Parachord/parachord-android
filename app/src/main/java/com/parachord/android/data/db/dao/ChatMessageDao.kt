package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.parachord.android.data.db.entity.ChatMessageEntity

@Dao
interface ChatMessageDao {

    /** Get all messages for a provider, ordered chronologically. */
    @Query("SELECT * FROM chat_messages WHERE providerId = :providerId ORDER BY timestamp ASC, id ASC")
    suspend fun getByProvider(providerId: String): List<ChatMessageEntity>

    /** Insert a single message. */
    @Insert
    suspend fun insert(entity: ChatMessageEntity): Long

    /** Insert multiple messages. */
    @Insert
    suspend fun insertAll(entities: List<ChatMessageEntity>)

    /** Clear all messages for a specific provider. */
    @Query("DELETE FROM chat_messages WHERE providerId = :providerId")
    suspend fun clearByProvider(providerId: String)

    /** Clear all chat messages (all providers). */
    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

    /** Delete messages older than the given timestamp (epoch millis). */
    @Query("DELETE FROM chat_messages WHERE timestamp < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Keep only the most recent [limit] messages per provider (trim old ones). */
    @Query(
        """DELETE FROM chat_messages
           WHERE providerId = :providerId
           AND id NOT IN (
               SELECT id FROM chat_messages
               WHERE providerId = :providerId
               ORDER BY timestamp DESC, id DESC
               LIMIT :limit
           )"""
    )
    suspend fun trimToLimit(providerId: String, limit: Int)
}
