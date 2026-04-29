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
 * Originally lived next to the Android-only `ChatGptProvider`; promoted to
 * shared so [AiChatService] can encode/decode tool messages without
 * depending on Android provider impls. Marked `internal` so plugin/UI code
 * doesn't reach in — they should go through `Json` directly.
 */
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
