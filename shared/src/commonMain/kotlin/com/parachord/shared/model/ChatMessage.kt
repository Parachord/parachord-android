package com.parachord.shared.model

data class ChatMessageRecord(
    val id: Long = 0,
    val providerId: String,
    val role: String,
    val content: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Long = 0L,
)
