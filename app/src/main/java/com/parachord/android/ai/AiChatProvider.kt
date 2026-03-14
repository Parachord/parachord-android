package com.parachord.android.ai

import kotlinx.serialization.json.JsonObject

/** Roles in the conversation, matching desktop's message format. */
enum class ChatRole { USER, ASSISTANT, SYSTEM, TOOL }

/** A single message in the conversation history. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    /** Tool name — needed for Gemini's functionResponse format. */
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

/** Interface that all AI providers implement. */
interface AiChatProvider {
    val id: String
    val name: String

    /**
     * Send messages with tool definitions to the AI and get a response.
     * Each provider translates to/from its native API format.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse
}

/** Schema definition for a DJ tool, sent to the AI provider. */
data class DjToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
