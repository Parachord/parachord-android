package com.parachord.android.ai.tools

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DjToolDefinitions — validates the tool schema structure.
 * These are the schemas sent to AI providers so correctness is critical.
 */
class DjToolDefinitionsTest {

    @Test
    fun `all returns exactly 8 tools`() {
        assertEquals(8, DjToolDefinitions.all().size)
    }

    @Test
    fun `all tool names are unique`() {
        val names = DjToolDefinitions.all().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `expected tool names are present`() {
        val names = DjToolDefinitions.all().map { it.name }.toSet()
        val expected = setOf(
            "play", "control", "search", "queue_add",
            "queue_clear", "create_playlist", "shuffle", "block_recommendation"
        )
        assertEquals(expected, names)
    }

    @Test
    fun `play tool has required artist and title parameters`() {
        val play = DjToolDefinitions.play
        assertEquals("play", play.name)
        val params = play.parameters.toString()
        assertTrue(params.contains("artist"))
        assertTrue(params.contains("title"))
        assertTrue("Play" in play.description || "play" in play.description.lowercase())
    }

    @Test
    fun `control tool has enum with pause resume skip previous`() {
        val control = DjToolDefinitions.control
        val params = control.parameters.toString()
        assertTrue(params.contains("pause"))
        assertTrue(params.contains("resume"))
        assertTrue(params.contains("skip"))
        assertTrue(params.contains("previous"))
    }

    @Test
    fun `search tool has query parameter`() {
        val search = DjToolDefinitions.search
        val params = search.parameters.toString()
        assertTrue(params.contains("query"))
        assertTrue(params.contains("limit"))
    }

    @Test
    fun `queue_add tool has tracks array parameter`() {
        val queueAdd = DjToolDefinitions.queueAdd
        val params = queueAdd.parameters.toString()
        assertTrue(params.contains("tracks"))
        assertTrue(params.contains("array"))
        assertTrue(params.contains("playFirst"))
    }

    @Test
    fun `queue_clear tool has no required parameters`() {
        val queueClear = DjToolDefinitions.queueClear
        val params = queueClear.parameters.toString()
        // required array should be empty
        assertTrue(params.contains("required"))
    }

    @Test
    fun `create_playlist tool requires name and tracks`() {
        val createPlaylist = DjToolDefinitions.createPlaylist
        val params = createPlaylist.parameters.toString()
        assertTrue(params.contains("name"))
        assertTrue(params.contains("tracks"))
    }

    @Test
    fun `shuffle tool has enabled boolean parameter`() {
        val shuffle = DjToolDefinitions.shuffle
        val params = shuffle.parameters.toString()
        assertTrue(params.contains("enabled"))
        assertTrue(params.contains("boolean"))
    }

    @Test
    fun `block_recommendation tool has type enum`() {
        val block = DjToolDefinitions.blockRecommendation
        val params = block.parameters.toString()
        assertTrue(params.contains("artist"))
        assertTrue(params.contains("album"))
        assertTrue(params.contains("track"))
        assertTrue(params.contains("type"))
    }

    @Test
    fun `all tools have non-empty descriptions`() {
        for (tool in DjToolDefinitions.all()) {
            assertTrue("Tool ${tool.name} has empty description", tool.description.isNotBlank())
        }
    }

    @Test
    fun `search tool warns against genre name searches`() {
        // Critical prompt engineering — AI must search for specific tracks, not genres
        val desc = DjToolDefinitions.search.description
        assertTrue(desc.contains("genre", ignoreCase = true))
    }
}
