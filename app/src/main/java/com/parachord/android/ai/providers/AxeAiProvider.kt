package com.parachord.android.ai.providers

import android.util.Log
import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatResponse
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.DjToolDefinition
import com.parachord.android.ai.ToolCall
import com.parachord.android.plugin.PluginManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AxeAiProvider"

/**
 * AI provider that delegates to an .axe plugin via [PluginManager].
 *
 * Allows .axe-only AI providers (e.g., Ollama) to participate in the
 * same AiChatService tool-call loop as native Kotlin providers. Also
 * provides dynamic model lists via the plugin's `listModels()` function.
 *
 * This is the bridge between the native Kotlin AI interface and the
 * JS-based .axe plugin system. Message format conversion happens here.
 */
class AxeAiProvider(
    override val id: String,
    override val name: String,
    private val pluginManager: PluginManager,
) : AiChatProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse {
        // Serialize messages to JSON format the .axe plugin expects
        val messagesJson = buildJsonArray {
            for (msg in messages) {
                add(buildJsonObject {
                    put("role", msg.role.name.lowercase())
                    put("content", msg.content)
                    val toolCalls = msg.toolCalls
                    if (toolCalls != null) {
                        put("toolCalls", buildJsonArray {
                            for (tc in toolCalls) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("arguments", json.encodeToJsonElement(
                                        kotlinx.serialization.serializer<Map<String, @kotlinx.serialization.Contextual Any?>>(),
                                        tc.arguments,
                                    ))
                                })
                            }
                        })
                    }
                    if (msg.toolCallId != null) put("toolCallId", msg.toolCallId)
                    if (msg.toolName != null) put("toolName", msg.toolName)
                })
            }
        }.toString()

        // Serialize tools
        val toolsJson = buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    tool.parameters?.let { params ->
                        put("parameters", params)
                    }
                })
            }
        }.toString()

        // Serialize config
        val configJson = buildJsonObject {
            put("apiKey", config.apiKey)
            put("model", config.model)
            if (config.endpoint.isNotBlank()) put("endpoint", config.endpoint)
        }.toString()

        val resultJson = pluginManager.aiChat(id, messagesJson, toolsJson, configJson)
            ?: throw Exception("$name returned no response")

        // Parse the result
        return try {
            val result = json.parseToJsonElement(resultJson).jsonObject
            val content = result["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val toolCalls = result["toolCalls"]?.jsonArray?.map { tc ->
                val obj = tc.jsonObject
                ToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = emptyMap(), // TODO: parse arguments from JSON
                )
            }
            AiChatResponse(content = content, toolCalls = toolCalls)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $name response: ${e.message}")
            // Treat entire result as text content
            AiChatResponse(content = resultJson)
        }
    }

    /**
     * Get available models from the .axe plugin.
     * Returns a list of model identifier strings.
     */
    suspend fun listModels(config: AiProviderConfig): List<String> {
        val configJson = buildJsonObject {
            put("apiKey", config.apiKey)
            if (config.endpoint.isNotBlank()) put("endpoint", config.endpoint)
        }.toString()

        val resultJson = pluginManager.listModels(id, configJson) ?: return emptyList()
        return try {
            val array = json.parseToJsonElement(resultJson).jsonArray
            array.map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse model list from $name: ${e.message}")
            emptyList()
        }
    }
}
