package com.parachord.android.data.db.entity

import org.junit.Assert.*
import org.junit.Test

class TrackEntityTest {

    // -- availableResolvers --

    @Test
    fun `availableResolvers includes spotify when spotifyId is set`() {
        val track = TrackEntity(id = "1", title = "T", artist = "A", spotifyId = "abc")
        assertTrue("spotify" in track.availableResolvers())
    }

    @Test
    fun `availableResolvers includes spotify when spotifyUri is set`() {
        val track = TrackEntity(id = "1", title = "T", artist = "A", spotifyUri = "spotify:track:abc")
        assertTrue("spotify" in track.availableResolvers())
    }

    @Test
    fun `availableResolvers includes applemusic when appleMusicId is set`() {
        val track = TrackEntity(id = "1", title = "T", artist = "A", appleMusicId = "123")
        assertTrue("applemusic" in track.availableResolvers())
    }

    @Test
    fun `availableResolvers includes soundcloud when soundcloudId is set`() {
        val track = TrackEntity(id = "1", title = "T", artist = "A", soundcloudId = "456")
        assertTrue("soundcloud" in track.availableResolvers())
    }

    @Test
    fun `availableResolvers includes stored resolver even without specific ID`() {
        val track = TrackEntity(id = "1", title = "T", artist = "A", resolver = "youtube")
        assertTrue("youtube" in track.availableResolvers())
    }

    @Test
    fun `availableResolvers does not duplicate resolver`() {
        val track = TrackEntity(
            id = "1", title = "T", artist = "A",
            resolver = "spotify", spotifyId = "abc"
        )
        val resolvers = track.availableResolvers()
        assertEquals(1, resolvers.count { it == "spotify" })
    }

    @Test
    fun `availableResolvers returns empty for track with no IDs`() {
        val track = TrackEntity(id = "1", title = "T", artist = "A")
        assertTrue(track.availableResolvers().isEmpty())
    }

    @Test
    fun `availableResolvers returns multiple resolvers`() {
        val track = TrackEntity(
            id = "1", title = "T", artist = "A",
            spotifyId = "abc", appleMusicId = "123", soundcloudId = "456"
        )
        val resolvers = track.availableResolvers()
        assertEquals(3, resolvers.size)
        assertTrue("spotify" in resolvers)
        assertTrue("applemusic" in resolvers)
        assertTrue("soundcloud" in resolvers)
    }

    @Test
    fun `availableResolvers sorts by resolver order`() {
        val track = TrackEntity(
            id = "1", title = "T", artist = "A",
            spotifyId = "abc", soundcloudId = "456", appleMusicId = "123"
        )
        // Custom order: soundcloud first
        val resolvers = track.availableResolvers(listOf("soundcloud", "applemusic", "spotify"))
        assertEquals("soundcloud", resolvers[0])
        assertEquals("applemusic", resolvers[1])
        assertEquals("spotify", resolvers[2])
    }

    @Test
    fun `availableResolvers puts unknown resolvers at end of sort`() {
        val track = TrackEntity(
            id = "1", title = "T", artist = "A",
            spotifyId = "abc", resolver = "bandcamp"
        )
        val resolvers = track.availableResolvers(listOf("spotify"))
        assertEquals("spotify", resolvers[0])
        assertEquals("bandcamp", resolvers[1])
    }

    @Test
    fun `availableResolvers ignores blank IDs`() {
        val track = TrackEntity(
            id = "1", title = "T", artist = "A",
            spotifyId = "", appleMusicId = "  ", soundcloudId = null
        )
        assertTrue(track.availableResolvers().isEmpty())
    }
}
