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
import kotlinx.serialization.json.JsonElement
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Gemini provider using Google AI API.
 * Mirrors desktop's gemini.axe chat implementation.
 *
 * Key differences:
 * - API key in URL query parameter, not header
 * - System prompt as `system_instruction`
 * - Roles: `user`/`model` (not `assistant`)
 * - Tool calls as `functionCall` parts, results as `functionResponse`
 */
@Singleton
class GeminiProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id = "gemini"
    override val name = "Google Gemini"

    /** Longer timeout client for AI generation — Gemini can take 30+ seconds with tools. */
    private val aiClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw Exception("Google AI API key not configured")

        val model = config.model.ifBlank { "gemini-2.0-flash" }

        // Convert messages to Gemini format
        var systemInstruction: JsonElement? = null
        val contents = buildJsonArray {
            for (msg in messages) {
                when (msg.role) {
                    ChatRole.SYSTEM -> {
                        systemInstruction = buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", msg.content) })
                            })
                        }
                    }
                    ChatRole.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                    ChatRole.ASSISTANT -> add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            // Include text part only if there's actual text content
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject { put("text", msg.content) })
                            }
                            // Echo back functionCall parts so Gemini sees its own tool calls
                            msg.toolCalls?.forEach { toolCall ->
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", toolCall.name)
                                        put("args", mapToJsonElement(toolCall.arguments))
                                    })
                                })
                            }
                            // If no text and no tool calls, send empty text to avoid empty parts array
                            if (msg.content.isBlank() && msg.toolCalls.isNullOrEmpty()) {
                                add(buildJsonObject { put("text", "") })
                            }
                        })
                    })
                    ChatRole.TOOL -> add(buildJsonObject {
                        put("role", "function")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", msg.toolName ?: msg.toolCallId ?: "")
                                    put("response", buildJsonObject {
                                        // Parse result as JSON object; fall back to wrapping as string
                                        val parsed = try {
                                            json.parseToJsonElement(msg.content)
                                        } catch (_: Exception) {
                                            buildJsonObject { put("result", msg.content) }
                                        }
                                        put("content", parsed)
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }

        // Convert tools to Gemini format
        val geminiTools = buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    }
                })
            })
        }

        val body = buildJsonObject {
            put("contents", contents)
            put("generationConfig", buildJsonObject { put("temperature", 0.7) })
            systemInstruction?.let { put("system_instruction", it) }
            if (tools.isNotEmpty()) put("tools", geminiTools)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${config.apiKey}"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = aiClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorData = try { json.parseToJsonElement(responseBody).jsonObject } catch (_: Exception) { null }
            val errorMsg = errorData?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "Gemini API error: ${response.code}"
            throw Exception(errorMsg)
        }

        val data = json.parseToJsonElement(responseBody).jsonObject

        // Check for prompt-level safety blocks (no candidates returned)
        val promptFeedback = data["promptFeedback"]?.jsonObject
        val blockReason = promptFeedback?.get("blockReason")?.jsonPrimitive?.content
        if (blockReason != null) {
            throw Exception("Gemini blocked the request: $blockReason")
        }

        val candidate = data["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw Exception("Gemini returned no response candidates")

        // Check candidate-level finish reason for safety blocks
        val finishReason = candidate["finishReason"]?.jsonPrimitive?.content
        if (finishReason == "SAFETY") {
            throw Exception("Gemini blocked the response due to safety filters")
        }

        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: buildJsonArray {}

        var content = ""
        val toolCalls = mutableListOf<ToolCall>()

        for (part in parts) {
            val partObj = part.jsonObject
            partObj["text"]?.jsonPrimitive?.content?.let { content += it }
            partObj["functionCall"]?.jsonObject?.let { fc ->
                toolCalls.add(ToolCall(
                    id = "call_${System.currentTimeMillis()}_${toolCalls.size}",
                    name = fc["name"]?.jsonPrimitive?.content ?: "",
                    arguments = jsonElementToMap(fc["args"] ?: buildJsonObject {}),
                ))
            }
        }

        AiChatResponse(content = content, toolCalls = toolCalls.takeIf { it.isNotEmpty() })
    }
}
