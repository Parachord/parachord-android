package com.parachord.android.ai

import com.parachord.android.ai.providers.mapToJsonElement
import com.parachord.android.ai.tools.DjToolDefinitions
import com.parachord.android.ai.tools.DjToolExecutor
import com.parachord.android.data.db.dao.ChatMessageDao
import com.parachord.android.data.db.entity.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates AI chat conversations with tool-call looping.
 * Mirrors the desktop app's ai-chat.js service logic.
 *
 * Messages are persisted to Room and retained for 30 days.
 */
@Singleton
class AiChatService @Inject constructor(
    private val toolExecutor: DjToolExecutor,
    private val contextProvider: ChatContextProvider,
    private val chatMessageDao: ChatMessageDao,
    private val json: Json,
) {

    companion object {
        private const val MAX_HISTORY_LENGTH = 50
        private const val MAX_TOOL_ITERATIONS = 5
        /** 30 days in milliseconds. */
        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    /** In-memory cache of per-provider histories, lazily populated from Room. */
    private val histories = mutableMapOf<String, MutableList<ChatMessage>>()
    private val loadedProviders = mutableSetOf<String>()

    /**
     * Ensure the in-memory history for [providerId] is populated from the database.
     * Also prunes messages older than 30 days on first load.
     */
    private suspend fun ensureLoaded(providerId: String) {
        if (providerId in loadedProviders) return

        withContext(Dispatchers.IO) {
            // Prune messages older than 30 days
            val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
            chatMessageDao.deleteOlderThan(cutoff)

            // Load from DB
            val entities = chatMessageDao.getByProvider(providerId)
            val messages = entities.map { it.toChatMessage() }.toMutableList()
            histories[providerId] = messages
            loadedProviders.add(providerId)
        }
    }

    /** Persist a single message to Room. */
    private suspend fun persistMessage(providerId: String, message: ChatMessage) {
        withContext(Dispatchers.IO) {
            chatMessageDao.insert(message.toEntity(providerId))
        }
    }

    /**
     * Send a user message to the given AI provider and return the final assistant response.
     * Handles tool-call loops automatically.
     *
     * @param onProgress Called with progress text when tools are being executed.
     */
    suspend fun sendMessage(
        provider: AiChatProvider,
        config: AiProviderConfig,
        userMessage: String,
        onProgress: (String) -> Unit = {},
    ): String {
        ensureLoaded(provider.id)
        val history = histories.getOrPut(provider.id) { mutableListOf() }

        // Add user message
        val userMsg = ChatMessage(role = ChatRole.USER, content = userMessage)
        history.add(userMsg)
        persistMessage(provider.id, userMsg)

        // Trim history: keep first message + last (N-1) if over limit
        trimHistory(history)

        // Build system prompt and prepend for the API call
        val systemPrompt = contextProvider.buildSystemPrompt()
        val systemMessage = ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt)
        val messagesWithSystem = listOf(systemMessage) + history

        val tools = DjToolDefinitions.all()

        val response = try {
            provider.chat(messagesWithSystem, tools, config)
        } catch (e: Exception) {
            val errorMessage = formatProviderError(e)
            // Add error as assistant message so it's visible in the UI
            val errorMsg = ChatMessage(role = ChatRole.ASSISTANT, content = errorMessage)
            history.add(errorMsg)
            persistMessage(provider.id, errorMsg)
            return errorMessage
        }

        // If no tool calls, just add the assistant message and return
        if (response.toolCalls.isNullOrEmpty()) {
            val assistantMsg = ChatMessage(role = ChatRole.ASSISTANT, content = response.content)
            history.add(assistantMsg)
            persistMessage(provider.id, assistantMsg)
            return response.content
        }

        // Enter tool loop
        return handleToolCalls(provider, config, history, tools, response, onProgress)
    }

    /**
     * Process tool calls in a loop until the AI returns a plain text response
     * or we hit MAX_TOOL_ITERATIONS.
     */
    private suspend fun handleToolCalls(
        provider: AiChatProvider,
        config: AiProviderConfig,
        history: MutableList<ChatMessage>,
        tools: List<DjToolDefinition>,
        initialResponse: AiChatResponse,
        onProgress: (String) -> Unit,
    ): String {
        var currentResponse = initialResponse
        var iterations = 0

        while (!currentResponse.toolCalls.isNullOrEmpty() && iterations < MAX_TOOL_ITERATIONS) {
            iterations++

            // Add assistant message with tool calls to history
            val assistantMsg = ChatMessage(
                role = ChatRole.ASSISTANT,
                content = currentResponse.content,
                toolCalls = currentResponse.toolCalls,
            )
            history.add(assistantMsg)
            persistMessage(provider.id, assistantMsg)

            // Execute each tool call and add results to history
            for (toolCall in currentResponse.toolCalls!!) {
                onProgress(toolProgressText(toolCall.name))

                val result = toolExecutor.execute(toolCall.name, toolCall.arguments)
                val resultJson = json.encodeToString(
                    JsonElement.serializer(),
                    mapToJsonElement(result),
                )

                val toolMsg = ChatMessage(
                    role = ChatRole.TOOL,
                    content = resultJson,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                )
                history.add(toolMsg)
                persistMessage(provider.id, toolMsg)
            }

            // Send follow-up with updated history
            val systemPrompt = contextProvider.buildSystemPrompt()
            val systemMessage = ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt)
            val messagesWithSystem = listOf(systemMessage) + history

            currentResponse = try {
                provider.chat(messagesWithSystem, tools, config)
            } catch (e: Exception) {
                return "I encountered an error while processing. Please try again."
            }
        }

        // Add final assistant message to history
        val finalContent = currentResponse.content
        val finalMsg = ChatMessage(role = ChatRole.ASSISTANT, content = finalContent)
        history.add(finalMsg)
        persistMessage(provider.id, finalMsg)
        return finalContent
    }

    /** Returns only USER and ASSISTANT messages for display in the UI. */
    suspend fun getDisplayMessages(providerId: String): List<ChatMessage> {
        ensureLoaded(providerId)
        val history = histories[providerId] ?: return emptyList()
        return history.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
    }

    /** Returns the full conversation history including tool messages. */
    suspend fun getFullHistory(providerId: String): List<ChatMessage> {
        ensureLoaded(providerId)
        return histories[providerId]?.toList() ?: emptyList()
    }

    /** Clear history for a specific provider. */
    suspend fun clearHistory(providerId: String) {
        histories.remove(providerId)
        loadedProviders.remove(providerId)
        withContext(Dispatchers.IO) {
            chatMessageDao.clearByProvider(providerId)
        }
    }

    /** Clear all provider histories. */
    suspend fun clearAllHistories() {
        histories.clear()
        loadedProviders.clear()
        withContext(Dispatchers.IO) {
            chatMessageDao.clearAll()
        }
    }

    /**
     * Trim history to MAX_HISTORY_LENGTH.
     * Keeps the first message (often important context) and the last (N-1) messages.
     */
    private fun trimHistory(history: MutableList<ChatMessage>) {
        if (history.size <= MAX_HISTORY_LENGTH) return

        val first = history.first()
        val tail = history.takeLast(MAX_HISTORY_LENGTH - 1)
        history.clear()
        history.add(first)
        history.addAll(tail)
    }

    /** Map provider exceptions to user-friendly error messages, matching desktop patterns. */
    private fun formatProviderError(e: Exception): String {
        val message = e.message ?: "Unknown error"

        return when {
            message.contains("ECONNREFUSED", ignoreCase = true) ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("Failed to connect", ignoreCase = true) ||
                message.contains("connect timed out", ignoreCase = true) ||
                message.contains("No address associated", ignoreCase = true) ->
                "Could not connect to the AI provider. Please check your internet connection and endpoint settings."

            message.contains("401") || message.contains("Unauthorized", ignoreCase = true) ->
                "Invalid API key. Please check your API key in settings."

            message.contains("429") || message.contains("Too Many Requests", ignoreCase = true) ||
                message.contains("rate limit", ignoreCase = true) ->
                "Rate limit exceeded. Please wait a moment and try again."

            message.contains("404") || message.contains("Not Found", ignoreCase = true) ->
                "Model not found. Please check the model name in settings."

            message.contains("500") || message.contains("Internal Server Error", ignoreCase = true) ->
                "The AI provider returned a server error. Please try again later."

            else -> "Error communicating with AI provider: $message"
        }
    }

    /** Map tool names to user-facing progress text. */
    private fun toolProgressText(name: String): String = when (name) {
        "play" -> "Playing..."
        "control" -> "Controlling playback..."
        "search" -> "Searching..."
        "queue_add" -> "Adding to queue..."
        "queue_clear" -> "Clearing queue..."
        "create_playlist" -> "Creating playlist..."
        "shuffle" -> "Toggling shuffle..."
        "block_recommendation" -> "Updating blocklist..."
        else -> "Processing..."
    }
}

// ── Entity ↔ ChatMessage Mapping ────────────────────────────────────

private fun ChatMessageEntity.toChatMessage(): ChatMessage {
    val toolCalls = toolCallsJson?.let { jsonStr ->
        try {
            val json = Json { ignoreUnknownKeys = true }
            val array = json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonArray
            array?.map { element ->
                val obj = element as kotlinx.serialization.json.JsonObject
                val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                val argsObj = obj["arguments"] as? kotlinx.serialization.json.JsonObject
                val args = argsObj?.let { com.parachord.android.ai.providers.jsonElementToMap(it) } ?: emptyMap()
                ToolCall(id = id, name = name, arguments = args)
            }
        } catch (_: Exception) {
            null
        }
    }

    return ChatMessage(
        role = ChatRole.valueOf(role),
        content = content,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        toolName = toolName,
    )
}

private fun ChatMessage.toEntity(providerId: String): ChatMessageEntity {
    val toolCallsJsonStr = toolCalls?.let { calls ->
        val array = kotlinx.serialization.json.buildJsonArray {
            for (call in calls) {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(call.id))
                    put("name", kotlinx.serialization.json.JsonPrimitive(call.name))
                    put("arguments", com.parachord.android.ai.providers.mapToJsonElement(call.arguments))
                })
            }
        }
        Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), array)
    }

    return ChatMessageEntity(
        providerId = providerId,
        role = role.name,
        content = content,
        toolCallsJson = toolCallsJsonStr,
        toolCallId = toolCallId,
        toolName = toolName,
    )
}
