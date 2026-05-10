package com.parachord.shared.deeplink

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Shared `Json` configured for protocol payload parsing.
 *
 * - `ignoreUnknownKeys = true` — desktop emits forward-compatible payloads
 *   that may add fields older Android builds don't know about; never fail
 *   parsing on unrecognized keys.
 * - `isLenient = false` — strict on the structural shape; we want a clean
 *   error rather than a silent misinterpretation.
 */
internal val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * Decode a UTF-8 base64-encoded JSON payload into a [JsonElement].
 *
 * **The naive `String(Base64.decode(payload))` mangles UTF-8** — em
 * dashes (`U+2014`), curly quotes (`U+2019`), Cyrillic, CJK, etc. all
 * lose the multi-byte sequence when the platform default charset is not
 * UTF-8 (Kotlin/Native and some JVMs default to platform-locale).
 * Always go through [ByteArray.decodeToString], which is contractually
 * UTF-8 on every Kotlin platform.
 *
 * Used by Phase 2's `play/playlist` and Phase 3's `play/radio` /
 * `listen-along` commands to ingest the `tracks=` and `pool=` query
 * parameters.
 *
 * Throws [IllegalArgumentException] on:
 * - Invalid base64 payloads (delegated from [Base64.decode]).
 * - Decoded bytes that aren't valid UTF-8.
 * - Decoded text that isn't a parseable JSON value.
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeBase64Utf8Json(payload: String): JsonElement {
    val bytes = try {
        Base64.decode(payload)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid base64 payload", e)
    }
    val text = bytes.decodeToString()
    return try {
        ProtocolJson.parseToJsonElement(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("Decoded payload is not valid JSON", e)
    }
}
