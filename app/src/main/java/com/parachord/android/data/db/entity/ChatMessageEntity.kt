package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted chat message for AI conversations.
 * Tool calls and tool results are serialized as JSON strings to keep the schema flat.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** AI provider id: "chatgpt", "claude", "gemini". */
    val providerId: String,
    /** Message role: USER, ASSISTANT, TOOL. SYSTEM messages are not persisted. */
    val role: String,
    /** Message text content. */
    val content: String,
    /** Serialized JSON array of tool calls (null if none). */
    val toolCallsJson: String? = null,
    /** Tool call ID for TOOL result messages. */
    val toolCallId: String? = null,
    /** Tool name for TOOL result messages. */
    val toolName: String? = null,
    /** Epoch millis when the message was created. */
    val timestamp: Long = System.currentTimeMillis(),
)
