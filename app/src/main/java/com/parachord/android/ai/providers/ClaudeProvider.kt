package com.parachord.android.ai.providers

import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatResponse
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.DjToolDefinition
import com.parachord.android.ai.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val DEFAULT_MODEL = "claude-sonnet-4-6-20250320"
private const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val MAX_TOKENS = 4096

/**
 * AI chat provider for Anthropic's Claude API.
 * Handles the Claude-specific message format where system prompts are top-level,
 * tool calls use `tool_use` content blocks, and tool results are user messages
 * with `tool_result` content blocks.
 */
class ClaudeProvider constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) : AiChatProvider {

    /** Longer timeout client for AI generation — large responses (e.g. recommendations) can take 30+ seconds. */
    private val aiClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override val id: String = "claude"
    override val name: String = "Claude"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse = withContext(Dispatchers.IO) {
        val apiKey = config.apiKey.ifBlank {
            throw IOException("Claude API key not configured")
        }
        val model = config.model.ifBlank { DEFAULT_MODEL }
        val endpoint = config.endpoint.ifBlank { DEFAULT_ENDPOINT }

        val requestBody = buildRequestBody(messages, tools, model)
        val requestJson = json.encodeToString(JsonObject.serializer(), requestBody)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = aiClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Claude")

        if (!response.isSuccessful) {
            throw IOException(parseError(response.code, responseBody))
        }

        parseResponse(responseBody)
    }

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        model: String,
    ): JsonObject {
        // Extract system prompt from messages (Claude uses a top-level system field)
        var systemPrompt: String? = null
        val apiMessages = mutableListOf<JsonObject>()

        for (msg in messages) {
            when (msg.role) {
                ChatRole.SYSTEM -> {
                    systemPrompt = msg.content
                }
                ChatRole.USER -> {
                    apiMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", msg.content)
                    })
                }
                ChatRole.ASSISTANT -> {
                    val toolCalls = msg.toolCalls
                    if (toolCalls.isNullOrEmpty()) {
                        // Plain text response
                        apiMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", msg.content)
                        })
                    } else {
                        // Assistant message with tool_use blocks
                        val contentBlocks = buildJsonArray {
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            for (tc in toolCalls) {
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", mapToJsonElement(tc.arguments))
                                })
                            }
                        }
                        apiMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", contentBlocks)
                        })
                    }
                }
                ChatRole.TOOL -> {
                    // Tool results go as user messages with tool_result content blocks
                    apiMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId ?: "")
                                put("content", msg.content)
                            })
                        })
                    })
                }
            }
        }

        return buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            if (systemPrompt != null) {
                put("system", systemPrompt)
            }
            put("messages", JsonArray(apiMessages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters?.let { put("input_schema", it) }
                        })
                    }
                })
            }
        }
    }

    private fun parseResponse(responseBody: String): AiChatResponse {
        val responseObj = json.parseToJsonElement(responseBody).jsonObject
        val contentBlocks = responseObj["content"]?.jsonArray ?: return AiChatResponse(content = "")

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        for (block in contentBlocks) {
            val blockObj = block.jsonObject
            when (blockObj["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    val text = blockObj["text"]?.jsonPrimitive?.content ?: ""
                    if (text.isNotBlank()) textParts.add(text)
                }
                "tool_use" -> {
                    val id = blockObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = blockObj["name"]?.jsonPrimitive?.content ?: ""
                    val input = blockObj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = id,
                            name = name,
                            arguments = jsonElementToMap(input),
                        )
                    )
                }
            }
        }

        return AiChatResponse(
            content = textParts.joinToString("\n"),
            toolCalls = toolCalls.ifEmpty { null },
        )
    }

    private fun parseError(statusCode: Int, responseBody: String): String {
        return try {
            val errorObj = json.parseToJsonElement(responseBody).jsonObject
            val errorType = errorObj["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            val errorMessage = errorObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content

            when {
                errorType == "authentication_error" ->
                    "Invalid API key. Please check your Claude API key in settings."
                statusCode == 429 ->
                    "Rate limit reached. Please wait a moment and try again."
                statusCode == 529 ->
                    "Claude is currently overloaded. Please try again in a few moments."
                errorMessage != null -> errorMessage
                else -> "Claude API error ($statusCode)"
            }
        } catch (_: Exception) {
            "Claude API error ($statusCode)"
        }
    }
}
