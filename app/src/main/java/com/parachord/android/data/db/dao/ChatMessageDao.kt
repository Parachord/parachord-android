package com.parachord.android.data.db.dao

import app.cash.sqldelight.db.SqlDriver
import com.parachord.shared.db.Chat_messages
import com.parachord.shared.db.ParachordDb
import com.parachord.android.data.db.entity.ChatMessageEntity
import com.parachord.shared.model.ChatMessageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [ChatMessageQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class ChatMessageDao(private val db: ParachordDb, private val driver: SqlDriver) {

    private val queries get() = db.chatMessageQueries

    /* ---- Mapping ---- */

    private fun Chat_messages.toChatMessageRecord() = ChatMessageRecord(
        id = id,
        providerId = providerId,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        timestamp = timestamp,
    )

    /* ---- Suspend one-shot reads ---- */

    /** Get all messages for a provider, ordered chronologically. */
    suspend fun getByProvider(providerId: String): List<ChatMessageEntity> = withContext(Dispatchers.IO) {
        queries.getByProvider(providerId).executeAsList().map { it.toChatMessageRecord() }
    }

    /* ---- Writes ---- */

    /** Insert a single message. Returns the row id via lastInsertedRowId. */
    suspend fun insert(entity: ChatMessageEntity): Long = withContext(Dispatchers.IO) {
        queries.insert(
            providerId = entity.providerId,
            role = entity.role,
            content = entity.content,
            toolCallsJson = entity.toolCallsJson,
            toolCallId = entity.toolCallId,
            toolName = entity.toolName,
            timestamp = entity.timestamp,
        )
        // SQLDelight INSERT doesn't return the row id directly; use the driver.
        driver.executeQuery(
            identifier = null,
            sql = "SELECT last_insert_rowid()",
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
            },
            parameters = 0,
        ).value
    }

    /** Insert multiple messages. */
    suspend fun insertAll(entities: List<ChatMessageEntity>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            for (entity in entities) {
                queries.insert(
                    providerId = entity.providerId,
                    role = entity.role,
                    content = entity.content,
                    toolCallsJson = entity.toolCallsJson,
                    toolCallId = entity.toolCallId,
                    toolName = entity.toolName,
                    timestamp = entity.timestamp,
                )
            }
        }
    }

    /** Clear all messages for a specific provider. */
    suspend fun clearByProvider(providerId: String): Unit = withContext(Dispatchers.IO) {
        queries.clearByProvider(providerId)
    }

    /** Clear all chat messages (all providers). */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        queries.clearAll()
    }

    /** Delete messages older than the given timestamp (epoch millis). */
    suspend fun deleteOlderThan(cutoffMillis: Long): Unit = withContext(Dispatchers.IO) {
        queries.deleteOlderThan(cutoffMillis)
    }

    /** Keep only the most recent [limit] messages per provider (trim old ones). */
    suspend fun trimToLimit(providerId: String, limit: Int): Unit = withContext(Dispatchers.IO) {
        queries.trimToLimit(
            providerId = providerId,
            providerId_ = providerId,
            `value` = limit.toLong(),
        )
    }
}
