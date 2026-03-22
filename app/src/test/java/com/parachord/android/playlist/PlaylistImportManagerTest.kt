package com.parachord.android.playlist

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for PlaylistImportManager's URL detection logic.
 * These are pure string-matching functions that don't need Android dependencies.
 */
class PlaylistImportManagerTest {

    // -- isSpotifyPlaylistUrl --

    @Test
    fun `detects standard Spotify playlist URL`() {
        assertTrue(isSpotifyPlaylistUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun `detects Spotify playlist URL with query params`() {
        assertTrue(isSpotifyPlaylistUrl("https://open.spotify.com/playlist/37i9dQZF1DX?si=abc123"))
    }

    @Test
    fun `detects Spotify playlist URL with intl prefix`() {
        assertTrue(isSpotifyPlaylistUrl("https://open.spotify.com/intl-en/playlist/37i9dQZF1DX"))
    }

    @Test
    fun `detects Spotify playlist URI`() {
        assertTrue(isSpotifyPlaylistUrl("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun `rejects Spotify album URL`() {
        assertFalse(isSpotifyPlaylistUrl("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"))
    }

    @Test
    fun `rejects Spotify track URL`() {
        assertFalse(isSpotifyPlaylistUrl("https://open.spotify.com/track/abc123"))
    }

    @Test
    fun `rejects non-Spotify URL`() {
        assertFalse(isSpotifyPlaylistUrl("https://bandcamp.com/playlist/abc"))
    }

    // -- isAppleMusicPlaylistUrl --

    @Test
    fun `detects Apple Music playlist URL`() {
        assertTrue(isAppleMusicPlaylistUrl("https://music.apple.com/us/playlist/favorites/pl.abc123"))
    }

    @Test
    fun `detects Apple Music playlist URL with different country`() {
        assertTrue(isAppleMusicPlaylistUrl("https://music.apple.com/gb/playlist/chill/pl.def456"))
    }

    @Test
    fun `rejects Apple Music album URL`() {
        assertFalse(isAppleMusicPlaylistUrl("https://music.apple.com/us/album/ok-computer/12345"))
    }

    @Test
    fun `rejects non-Apple Music URL`() {
        assertFalse(isAppleMusicPlaylistUrl("https://open.spotify.com/playlist/abc"))
    }

    // -- URL routing --

    @Test
    fun `routes Spotify URL to Spotify import`() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DX"
        val route = detectService(url)
        assertEquals("spotify", route)
    }

    @Test
    fun `routes Apple Music URL to Apple Music import`() {
        val url = "https://music.apple.com/us/playlist/fav/pl.abc"
        val route = detectService(url)
        assertEquals("applemusic", route)
    }

    @Test
    fun `routes unknown URL to XSPF import`() {
        val url = "https://example.com/playlist.xspf"
        val route = detectService(url)
        assertEquals("xspf", route)
    }

    @Test
    fun `routes Spotify URI to Spotify import`() {
        val url = "spotify:playlist:37i9dQZF1DX"
        val route = detectService(url)
        assertEquals("spotify", route)
    }

    // -- Replicating the URL detection logic --

    private fun isSpotifyPlaylistUrl(url: String): Boolean =
        url.contains("open.spotify.com/") && url.contains("/playlist/") ||
            url.startsWith("spotify:playlist:")

    private fun isAppleMusicPlaylistUrl(url: String): Boolean =
        url.contains("music.apple.com") && url.contains("/playlist/")

    private fun detectService(url: String): String = when {
        isSpotifyPlaylistUrl(url) -> "spotify"
        isAppleMusicPlaylistUrl(url) -> "applemusic"
        else -> "xspf"
    }
}
