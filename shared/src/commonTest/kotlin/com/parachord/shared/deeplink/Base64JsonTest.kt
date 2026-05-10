package com.parachord.shared.deeplink

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalEncodingApi::class)
class Base64JsonTest {

    @Test
    fun roundTrip_basicAscii() {
        val payload = """{"title":"Hello","tracks":[]}"""
        val encoded = Base64.encode(payload.encodeToByteArray())
        val decoded = decodeBase64Utf8Json(encoded) as JsonObject
        assertEquals("Hello", decoded["title"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun roundTrip_emDash() {
        // U+2014 — three bytes in UTF-8 (E2 80 94). Any decoder using
        // platform-default charset on Latin-1 systems mangles this.
        val payload = """{"title":"Side A — Side B"}"""
        val encoded = Base64.encode(payload.encodeToByteArray())
        val decoded = decodeBase64Utf8Json(encoded) as JsonObject
        assertEquals("Side A — Side B", decoded["title"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun roundTrip_curlyApostrophe() {
        // U+2019 — common in titles ("Don't" → "Don't"). E2 80 99.
        val payload = """{"title":"Don’t Stop"}"""
        val encoded = Base64.encode(payload.encodeToByteArray())
        val decoded = decodeBase64Utf8Json(encoded) as JsonObject
        assertEquals("Don’t Stop", decoded["title"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun roundTrip_cyrillic() {
        val payload = """{"artist":"Кино","title":"Пачка сигарет"}"""
        val encoded = Base64.encode(payload.encodeToByteArray())
        val decoded = decodeBase64Utf8Json(encoded) as JsonObject
        assertEquals("Кино", decoded["artist"]!!.jsonPrimitive.contentOrNull)
        assertEquals("Пачка сигарет", decoded["title"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun roundTrip_cjk() {
        // 4-byte UTF-8 sequences for Han characters.
        val payload = """{"artist":"中島美嘉","title":"雪の華"}"""
        val encoded = Base64.encode(payload.encodeToByteArray())
        val decoded = decodeBase64Utf8Json(encoded) as JsonObject
        assertEquals("中島美嘉", decoded["artist"]!!.jsonPrimitive.contentOrNull)
        assertEquals("雪の華", decoded["title"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun roundTrip_emoji() {
        // Astral plane: U+1F3B5 musical note (4 bytes UTF-8).
        val payload = """{"title":"🎵 Hot Tracks"}"""
        val encoded = Base64.encode(payload.encodeToByteArray())
        val decoded = decodeBase64Utf8Json(encoded) as JsonObject
        assertEquals("🎵 Hot Tracks", decoded["title"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun rejectsInvalidBase64() {
        assertFailsWith<IllegalArgumentException> {
            decodeBase64Utf8Json("!!!not valid base64!!!")
        }
    }

    @Test
    fun rejectsInvalidJson() {
        val notJson = Base64.encode("this is not json".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> {
            decodeBase64Utf8Json(notJson)
        }
    }
}
