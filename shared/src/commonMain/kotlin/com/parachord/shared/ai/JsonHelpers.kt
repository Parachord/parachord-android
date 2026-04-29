package com.parachord.shared.ai

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
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON conversion helpers for AI provider payloads (tool args/results).
 * Used by [AiChatService] to encode/decode tool messages, and by the three
 * concrete provider implementations (ChatGPT/Claude/Gemini) to translate
 * between their respective wire formats and the canonical `Map<String,Any?>`
 * tool-arg shape carried in [com.parachord.shared.ai.ToolCall].
 *
 * Public visibility (originally `internal`) so the Android `:app` provider
 * tests in `app/src/test/.../ai/providers/AiProviderMessageFormattingTest.kt`
 * can roundtrip through the same code paths without duplicating the helpers.
 */
fun mapToJsonElement(map: Map<String, Any?>): JsonElement = buildJsonObject {
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

fun jsonElementToMap(element: JsonElement): Map<String, Any?> {
    if (element !is JsonObject) return emptyMap()
    return element.entries.associate { (key, value) -> key to jsonElementToValue(value) }
}

fun jsonElementToValue(element: JsonElement): Any? = when (element) {
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
