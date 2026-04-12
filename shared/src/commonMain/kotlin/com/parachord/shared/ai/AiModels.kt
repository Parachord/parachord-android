package com.parachord.shared.ai

import kotlinx.serialization.json.JsonObject

/** Roles in the conversation, matching desktop's message format. */
enum class ChatRole { USER, ASSISTANT, SYSTEM, TOOL }

/** A single message in the conversation history. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
)

/** A tool call requested by the AI. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
)

/** Response from an AI provider. */
data class AiChatResponse(
    val content: String,
    val toolCalls: List<ToolCall>? = null,
)

/** Configuration for an AI provider (API key, model, endpoint). */
data class AiProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
)

/** Metadata about an available AI provider for the UI. */
data class AiProviderInfo(
    val id: String,
    val name: String,
    val isConfigured: Boolean,
    val currentModel: String,
)

/** Schema definition for a DJ tool, sent to the AI provider. */
data class DjToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
)

/** Interface that all AI providers implement. */
interface AiChatProvider {
    val id: String
    val name: String

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse
}
