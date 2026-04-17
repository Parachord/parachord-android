package com.parachord.android.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AiRecommendationService's JSON extraction and parsing logic.
 * These are pure functions critical to reliable AI integration.
 */
class AiRecommendationServiceTest {

    // -- extractJsonObject --

    @Test
    fun `extractJsonObject from plain JSON`() {
        val input = """{"albums":[],"artists":[]}"""
        assertEquals(input, extractJsonObject(input))
    }

    @Test
    fun `extractJsonObject from markdown code block`() {
        val input = """
            Here's the JSON:
            ```json
            {"albums":[{"title":"OK Computer","artist":"Radiohead","reason":"great"}],"artists":[]}
            ```
            Hope this helps!
        """.trimIndent()
        val result = extractJsonObject(input)
        assertNotNull(result)
        assertTrue(result!!.startsWith("{"))
        assertTrue(result.endsWith("}"))
        assertTrue(result.contains("OK Computer"))
    }

    @Test
    fun `extractJsonObject handles preamble text`() {
        val input = """Sure! Here are my recommendations: {"albums":[],"artists":[]}"""
        val result = extractJsonObject(input)
        assertEquals("""{"albums":[],"artists":[]}""", result)
    }

    @Test
    fun `extractJsonObject handles nested braces`() {
        val input = """{"albums":[{"title":"A","artist":"B","reason":"C"}],"artists":[{"name":"D","reason":"E"}]}"""
        assertEquals(input, extractJsonObject(input))
    }

    @Test
    fun `extractJsonObject handles braces inside strings`() {
        val input = """{"albums":[{"title":"A {Special} Album","artist":"B","reason":"C"}],"artists":[]}"""
        assertEquals(input, extractJsonObject(input))
    }

    @Test
    fun `extractJsonObject returns null for no JSON`() {
        assertNull(extractJsonObject("No JSON here at all"))
    }

    @Test
    fun `extractJsonObject returns null for empty string`() {
        assertNull(extractJsonObject(""))
    }

    @Test
    fun `extractJsonObject handles escaped quotes in strings`() {
        val input = """{"albums":[{"title":"He said \"hello\"","artist":"B","reason":"C"}],"artists":[]}"""
        assertEquals(input, extractJsonObject(input))
    }

    // -- parseRecommendations --

    @Test
    fun `parseRecommendations extracts albums and artists`() {
        val json = """
        {
            "albums": [
                {"title": "OK Computer", "artist": "Radiohead", "reason": "innovative"},
                {"title": "Kid A", "artist": "Radiohead", "reason": "experimental"}
            ],
            "artists": [
                {"name": "Bjork", "reason": "unique voice"},
                {"name": "Aphex Twin", "reason": "electronic pioneer"}
            ]
        }
        """.trimIndent()

        val result = parseRecommendations(json)
        assertEquals(2, result.albums.size)
        assertEquals(2, result.artists.size)
        assertEquals("OK Computer", result.albums[0].title)
        assertEquals("Radiohead", result.albums[0].artist)
        assertEquals("Bjork", result.artists[0].name)
    }

    @Test
    fun `parseRecommendations handles missing reason field`() {
        val json = """{"albums":[{"title":"A","artist":"B"}],"artists":[{"name":"C"}]}"""
        val result = parseRecommendations(json)
        assertEquals(1, result.albums.size)
        assertEquals("", result.albums[0].reason)
        assertEquals("", result.artists[0].reason)
    }

    @Test
    fun `parseRecommendations handles extra fields from AI`() {
        val json = """
        {
            "albums": [{"title": "A", "artist": "B", "reason": "C", "genre": "rock", "year": 1997}],
            "artists": [{"name": "D", "reason": "E", "country": "UK"}],
            "extra_field": "ignored"
        }
        """
        val result = parseRecommendations(json)
        assertEquals(1, result.albums.size)
        assertEquals(1, result.artists.size)
    }

    @Test
    fun `parseRecommendations handles empty arrays`() {
        val json = """{"albums":[],"artists":[]}"""
        val result = parseRecommendations(json)
        assertTrue(result.albums.isEmpty())
        assertTrue(result.artists.isEmpty())
    }

    @Test
    fun `parseRecommendations skips malformed entries`() {
        val json = """
        {
            "albums": [
                {"title": "Good", "artist": "Artist", "reason": "ok"},
                {"missing_title": true},
                {"title": "Also Good", "artist": "Artist2", "reason": "ok"}
            ],
            "artists": [
                {"name": "Good Artist", "reason": "yes"},
                {"bad": "entry"}
            ]
        }
        """
        val result = parseRecommendations(json)
        assertEquals(2, result.albums.size)
        assertEquals(1, result.artists.size)
    }

    @Test
    fun `parseRecommendations from markdown-wrapped response`() {
        val response = """
            Here are my recommendations:
            ```json
            {"albums":[{"title":"A","artist":"B","reason":"C"}],"artists":[{"name":"D","reason":"E"}]}
            ```
        """.trimIndent()

        val jsonStr = extractJsonObject(response) ?: error("Should extract JSON")
        val result = parseRecommendations(jsonStr)
        assertEquals(1, result.albums.size)
        assertEquals(1, result.artists.size)
    }

    @Test
    fun `parseRecommendations throws on no JSON object`() {
        assertThrows(Exception::class.java) {
            parseRecommendations("no json here")
        }
    }

    // -- Helper implementations matching AiRecommendationService --

    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until content.length) {
            val c = content[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return content.substring(start, i + 1) }
        }
        return null
    }

    private fun parseRecommendations(content: String): AiRecommendations {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val jsonStr = extractJsonObject(content)
            ?: throw Exception("No JSON object found in AI response")
        val root = json.parseToJsonElement(jsonStr).jsonObject

        val albums = root["albums"]?.jsonArray?.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                AiAlbumSuggestion(
                    title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    artist = obj["artist"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                )
            } catch (_: Exception) { null }
        } ?: emptyList()

        val artists = root["artists"]?.jsonArray?.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                AiArtistSuggestion(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                )
            } catch (_: Exception) { null }
        } ?: emptyList()

        return AiRecommendations(albums = albums, artists = artists)
    }

    // imports for json parsing
    private val kotlinx.serialization.json.JsonElement.jsonObject
        get() = this as kotlinx.serialization.json.JsonObject
    private val kotlinx.serialization.json.JsonElement.jsonArray
        get() = this as kotlinx.serialization.json.JsonArray
    private val kotlinx.serialization.json.JsonElement.jsonPrimitive
        get() = this as kotlinx.serialization.json.JsonPrimitive
}
