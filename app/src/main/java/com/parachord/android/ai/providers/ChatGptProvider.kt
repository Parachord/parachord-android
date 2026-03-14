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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatGptProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id: String = "chatgpt"
    override val name: String = "ChatGPT"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse {
        val endpoint = config.endpoint.ifBlank { "https://api.openai.com/v1/chat/completions" }
        val model = config.model.ifBlank { "gpt-4o-mini" }

        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                for (msg in messages) {
                    add(messageToJson(msg))
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(toolToJson(tool))
                    }
                })
            }
        }

        val requestBody = json.encodeToString(JsonElement.serializer(), body)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMessage = try {
                    val errorJson = json.parseToJsonElement(responseBody).jsonObject
                    errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }
                throw Exception("ChatGPT API error (${response.code}): $errorMessage")
            }

            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val choice = responseJson["choices"]!!.jsonArray[0].jsonObject
            val message = choice["message"]!!.jsonObject

            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val toolCalls = message["tool_calls"]?.jsonArray?.map { tc ->
                val tcObj = tc.jsonObject
                val function = tcObj["function"]!!.jsonObject
                val argsStr = function["arguments"]!!.jsonPrimitive.content
                val argsMap = try {
                    jsonElementToMap(json.parseToJsonElement(argsStr))
                } catch (_: Exception) {
                    emptyMap()
                }
                ToolCall(
                    id = tcObj["id"]!!.jsonPrimitive.content,
                    name = function["name"]!!.jsonPrimitive.content,
                    arguments = argsMap,
                )
            }

            AiChatResponse(
                content = content,
                toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun messageToJson(msg: ChatMessage): JsonObject = buildJsonObject {
        when (msg.role) {
            ChatRole.USER -> {
                put("role", "user")
                put("content", msg.content)
            }
            ChatRole.SYSTEM -> {
                put("role", "system")
                put("content", msg.content)
            }
            ChatRole.ASSISTANT -> {
                put("role", "assistant")
                if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                    put("content", JsonNull)
                    put("tool_calls", buildJsonArray {
                        for (tc in msg.toolCalls) {
                            add(buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", json.encodeToString(
                                        JsonElement.serializer(),
                                        mapToJsonElement(tc.arguments),
                                    ))
                                })
                            })
                        }
                    })
                } else {
                    put("content", msg.content)
                }
            }
            ChatRole.TOOL -> {
                put("role", "tool")
                put("tool_call_id", msg.toolCallId ?: "")
                put("content", msg.content)
            }
        }
    }

    private fun toolToJson(tool: DjToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        })
    }
}

internal fun mapToJsonElement(map: Map<String, Any?>): JsonElement = buildJsonObject {
    for ((key, value) in map) {
        put(key, valueToJsonElement(value))
    }
}

private fun valueToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        mapToJsonElement(value as Map<String, Any?>)
    }
    is List<*> -> buildJsonArray {
        for (item in value) {
            add(valueToJsonElement(item))
        }
    }
    is JsonElement -> value
    else -> JsonPrimitive(value.toString())
}

internal fun jsonElementToMap(element: JsonElement): Map<String, Any?> {
    if (element !is JsonObject) return emptyMap()
    return element.entries.associate { (key, value) -> key to jsonElementToValue(value) }
}

internal fun jsonElementToValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> {
        element.booleanOrNull
            ?: element.longOrNull
            ?: element.doubleOrNull
            ?: element.contentOrNull
    }
    is JsonObject -> jsonElementToMap(element)
    is JsonArray -> element.map { jsonElementToValue(it) }
}
