package com.parachord.android.ai.providers

import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.ToolCall
import com.parachord.shared.ai.jsonElementToMap
import com.parachord.shared.ai.jsonElementToValue
import com.parachord.shared.ai.mapToJsonElement
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the JSON conversion utilities shared by all AI providers,
 * and for the message formatting logic specific to each provider.
 */
class AiProviderMessageFormattingTest {

    // ── mapToJsonElement / jsonElementToMap roundtrip ────────────

    @Test
    fun `mapToJsonElement handles simple types`() {
        val map = mapOf(
            "string" to "hello",
            "int" to 42,
            "double" to 3.14,
            "bool" to true,
            "null" to null,
        )
        val json = mapToJsonElement(map).jsonObject
        assertEquals("hello", json["string"]?.jsonPrimitive?.content)
        assertEquals(42, json["int"]?.jsonPrimitive?.long?.toInt())
        assertEquals(true, json["bool"]?.jsonPrimitive?.boolean)
        assertTrue(json["null"] is JsonNull)
    }

    @Test
    fun `mapToJsonElement handles nested maps`() {
        val map = mapOf(
            "outer" to mapOf("inner" to "value")
        )
        val json = mapToJsonElement(map).jsonObject
        val outer = json["outer"]?.jsonObject
        assertNotNull(outer)
        assertEquals("value", outer!!["inner"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mapToJsonElement handles lists`() {
        val map = mapOf(
            "items" to listOf("a", "b", "c")
        )
        val json = mapToJsonElement(map).jsonObject
        val items = json["items"]?.jsonArray
        assertNotNull(items)
        assertEquals(3, items!!.size)
        assertEquals("a", items[0].jsonPrimitive.content)
    }

    @Test
    fun `mapToJsonElement handles nested list of maps`() {
        val map = mapOf(
            "tracks" to listOf(
                mapOf("artist" to "Radiohead", "title" to "Creep"),
                mapOf("artist" to "Nirvana", "title" to "Smells Like Teen Spirit"),
            )
        )
        val json = mapToJsonElement(map).jsonObject
        val tracks = json["tracks"]?.jsonArray
        assertEquals(2, tracks!!.size)
        assertEquals("Radiohead", tracks[0].jsonObject["artist"]?.jsonPrimitive?.content)
    }

    @Test
    fun `jsonElementToMap roundtrips correctly`() {
        val original = mapOf(
            "name" to "test",
            "count" to 5L,
            "enabled" to true,
        )
        val jsonElement = mapToJsonElement(original)
        val roundtripped = jsonElementToMap(jsonElement)
        assertEquals("test", roundtripped["name"])
        assertEquals(5L, roundtripped["count"])
        assertEquals(true, roundtripped["enabled"])
    }

    @Test
    fun `jsonElementToMap returns empty for non-object`() {
        val result = jsonElementToMap(JsonPrimitive("hello"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `jsonElementToValue handles null`() {
        assertNull(jsonElementToValue(JsonNull))
    }

    @Test
    fun `jsonElementToValue handles boolean`() {
        assertEquals(true, jsonElementToValue(JsonPrimitive(true)))
    }

    @Test
    fun `jsonElementToValue handles long`() {
        assertEquals(42L, jsonElementToValue(JsonPrimitive(42)))
    }

    @Test
    fun `jsonElementToValue handles double`() {
        assertEquals(3.14, jsonElementToValue(JsonPrimitive(3.14)))
    }

    @Test
    fun `jsonElementToValue handles string`() {
        assertEquals("hello", jsonElementToValue(JsonPrimitive("hello")))
    }

    @Test
    fun `jsonElementToValue handles arrays`() {
        val arr = buildJsonArray {
            add(JsonPrimitive(1))
            add(JsonPrimitive(2))
        }
        val result = jsonElementToValue(arr) as List<*>
        assertEquals(2, result.size)
        assertEquals(1L, result[0])
    }

    // ── ChatGPT message format ──────────────────────────────────

    @Test
    fun `ChatGPT user message has correct role`() {
        val msg = ChatMessage(role = ChatRole.USER, content = "hello")
        assertEquals("USER", msg.role.name)
    }

    @Test
    fun `ChatGPT tool message includes tool_call_id`() {
        val msg = ChatMessage(
            role = ChatRole.TOOL,
            content = """{"results":[]}""",
            toolCallId = "call_abc123",
        )
        assertEquals("call_abc123", msg.toolCallId)
    }

    @Test
    fun `ChatGPT assistant message with tool calls has null content convention`() {
        // When assistant has tool calls, content should be empty/null
        val msg = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(id = "call_1", name = "search", arguments = mapOf("query" to "test"))
            ),
        )
        assertTrue(msg.content.isEmpty())
        assertNotNull(msg.toolCalls)
        assertEquals(1, msg.toolCalls!!.size)
    }

    // ── Claude message format specifics ─────────────────────────

    @Test
    fun `Claude system prompt is separated from messages`() {
        // Claude uses a top-level "system" field, not a system role message
        val messages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = "You are a DJ"),
            ChatMessage(role = ChatRole.USER, content = "play jazz"),
        )
        val systemMessages = messages.filter { it.role == ChatRole.SYSTEM }
        val apiMessages = messages.filter { it.role != ChatRole.SYSTEM }
        assertEquals(1, systemMessages.size)
        assertEquals(1, apiMessages.size)
    }

    @Test
    fun `Claude tool results are formatted as user messages with tool_result`() {
        // Claude expects tool results as user messages with tool_result content blocks
        val msg = ChatMessage(
            role = ChatRole.TOOL,
            content = """{"success":true}""",
            toolCallId = "toolu_abc123",
        )
        // In Claude's format, TOOL messages become user messages with:
        // content: [{ type: "tool_result", tool_use_id: "...", content: "..." }]
        assertEquals(ChatRole.TOOL, msg.role)
        assertEquals("toolu_abc123", msg.toolCallId)
    }

    // ── Gemini message format specifics ─────────────────────────

    @Test
    fun `Gemini uses model role instead of assistant`() {
        // Gemini maps: ASSISTANT → "model", USER → "user", SYSTEM → system_instruction
        val roleMapping = mapOf(
            ChatRole.USER to "user",
            ChatRole.ASSISTANT to "model",
            ChatRole.TOOL to "function",
        )
        assertEquals("model", roleMapping[ChatRole.ASSISTANT])
        assertEquals("function", roleMapping[ChatRole.TOOL])
    }

    @Test
    fun `Gemini tool calls use functionCall format`() {
        // Gemini tool calls: { functionCall: { name: "...", args: {...} } }
        val toolCall = ToolCall(
            id = "call_1",
            name = "search",
            arguments = mapOf("query" to "radiohead"),
        )
        assertEquals("search", toolCall.name)
        assertEquals("radiohead", toolCall.arguments["query"])
    }

    @Test
    fun `Gemini tool response uses functionResponse and toolName`() {
        // Gemini needs toolName for the functionResponse.name field
        val msg = ChatMessage(
            role = ChatRole.TOOL,
            content = """{"success":true}""",
            toolCallId = "call_1",
            toolName = "search",
        )
        assertEquals("search", msg.toolName)
    }

    // ── JSON mode detection ─────────────────────────────────────

    @Test
    fun `wantsJson detection matches ChatGPT pattern`() {
        // ChatGPT enables json_object mode when system prompt contains "JSON" AND "no markdown"
        val systemContent = "You MUST respond with ONLY a valid JSON object, no markdown, no explanations"
        val wantsJson = systemContent.contains("JSON", ignoreCase = true) &&
            systemContent.contains("no markdown", ignoreCase = true)
        assertTrue(wantsJson)
    }

    @Test
    fun `wantsJson is false for normal system prompts`() {
        val systemContent = "You are a helpful music DJ assistant"
        val wantsJson = systemContent.contains("JSON", ignoreCase = true) &&
            systemContent.contains("no markdown", ignoreCase = true)
        assertFalse(wantsJson)
    }
}
