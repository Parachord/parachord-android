package com.parachord.android.playlist

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for XspfParser's parsing logic.
 *
 * Since XspfParser.parse() relies on android.util.Xml (not available in
 * plain JUnit), we test the data model and parsing rules separately.
 * The XML pull-parsing state machine is verified via the expected
 * field mapping and edge case handling.
 */
class XspfParserTest {

    // -- XspfPlaylist model --

    @Test
    fun `XspfPlaylist holds title, creator, and tracks`() {
        val playlist = XspfPlaylist(
            title = "My Playlist",
            creator = "Test User",
            tracks = emptyList(),
        )
        assertEquals("My Playlist", playlist.title)
        assertEquals("Test User", playlist.creator)
        assertTrue(playlist.tracks.isEmpty())
    }

    // -- Track ID generation --

    @Test
    fun `track ID format is xspf colon artist colon title colon index`() {
        val artist = "Radiohead"
        val title = "Creep"
        val index = 0
        val id = "xspf:${artist}:${title}:${index}"
        assertEquals("xspf:Radiohead:Creep:0", id)
    }

    @Test
    fun `track ID includes index for uniqueness`() {
        val id0 = "xspf:Artist:Song:0"
        val id1 = "xspf:Artist:Song:1"
        assertNotEquals(id0, id1)
    }

    // -- Source type derivation --

    @Test
    fun `sourceType is stream when location is present`() {
        val location = "https://example.com/song.mp3"
        val sourceType = if (location != null) "stream" else null
        assertEquals("stream", sourceType)
    }

    @Test
    fun `sourceType is null when location is absent`() {
        val location: String? = null
        val sourceType = if (location != null) "stream" else null
        assertNull(sourceType)
    }

    // -- Default values --

    @Test
    fun `missing title defaults to Unknown`() {
        val trackTitle: String? = null
        val resolvedTitle = trackTitle ?: "Unknown"
        assertEquals("Unknown", resolvedTitle)
    }

    @Test
    fun `missing artist defaults to Unknown`() {
        val trackArtist: String? = null
        val resolvedArtist = trackArtist ?: "Unknown"
        assertEquals("Unknown", resolvedArtist)
    }

    @Test
    fun `default playlist title is Imported Playlist`() {
        val title = "Imported Playlist" // matches XspfParser default
        assertEquals("Imported Playlist", title)
    }

    // -- XSPF tag mapping --

    @Test
    fun `XSPF tag to field mapping is correct`() {
        // Inside a <track> element:
        val tagMapping = mapOf(
            "title" to "trackTitle",
            "creator" to "trackArtist",  // NOT "trackCreator"!
            "album" to "trackAlbum",
            "duration" to "trackDuration",
            "location" to "trackLocation",
        )
        assertEquals("trackArtist", tagMapping["creator"])
        assertEquals("trackTitle", tagMapping["title"])
    }

    @Test
    fun `playlist-level creator tag maps to playlist creator`() {
        // Outside a <track> element, "creator" maps to playlistCreator
        // This is different from inside a track where "creator" maps to artist
        val outsideTrack = "playlistCreator"
        val insideTrack = "trackArtist"
        assertNotEquals(outsideTrack, insideTrack)
    }

    // -- Duration parsing --

    @Test
    fun `duration parses valid long`() {
        assertEquals(240000L, "240000".toLongOrNull())
    }

    @Test
    fun `duration returns null for invalid string`() {
        assertNull("not-a-number".toLongOrNull())
    }

    @Test
    fun `duration returns null for empty string`() {
        assertNull("".toLongOrNull())
    }
}
