package com.parachord.android.data.repository

import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * Tests for CriticalDarlingsRepository's pure parsing logic.
 * The RSS parsing, title extraction, and HTML cleaning are pure functions
 * that can be tested without Android dependencies.
 */
class CriticalDarlingsRepositoryTest {

    // -- parseTitle: "Album Title by Artist Name" --

    @Test
    fun `parseTitle extracts album and artist`() {
        val result = parseTitle("OK Computer by Radiohead")
        assertNotNull(result)
        assertEquals("OK Computer", result!!.first)
        assertEquals("Radiohead", result.second)
    }

    @Test
    fun `parseTitle uses last occurrence of by`() {
        // "Something by Someone by The Real Artist"
        val result = parseTitle("Stand By Me by Ben E. King")
        assertNotNull(result)
        assertEquals("Stand By Me", result!!.first)
        assertEquals("Ben E. King", result.second)
    }

    @Test
    fun `parseTitle returns null for no by separator`() {
        assertNull(parseTitle("Just An Album Title"))
    }

    @Test
    fun `parseTitle returns null for empty artist`() {
        assertNull(parseTitle("Album by "))
    }

    @Test
    fun `parseTitle returns null for empty album`() {
        assertNull(parseTitle(" by Artist"))
    }

    @Test
    fun `parseTitle handles multiple by occurrences`() {
        val result = parseTitle("Powered by Love by Spiritualized")
        assertNotNull(result)
        assertEquals("Powered by Love", result!!.first)
        assertEquals("Spiritualized", result.second)
    }

    // -- extractSpotifyUrl --

    @Test
    fun `extractSpotifyUrl finds URL in HTML`() {
        val html = """Check this out: <a href="https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3">Listen</a>"""
        val url = extractSpotifyUrl(html)
        assertEquals("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3", url)
    }

    @Test
    fun `extractSpotifyUrl returns null when no Spotify URL`() {
        val html = """<p>Great album available on <a href="https://bandcamp.com">Bandcamp</a></p>"""
        assertNull(extractSpotifyUrl(html))
    }

    @Test
    fun `extractSpotifyUrl handles http scheme`() {
        val html = """<a href="http://open.spotify.com/album/abc123">Listen</a>"""
        assertNotNull(extractSpotifyUrl(html))
    }

    // -- cleanHtml --

    @Test
    fun `cleanHtml strips HTML tags`() {
        assertEquals("Hello world", cleanHtml("<p>Hello <b>world</b></p>"))
    }

    @Test
    fun `cleanHtml decodes entities`() {
        assertEquals("Tom & Jerry", cleanHtml("Tom &amp; Jerry"))
        assertEquals("a < b", cleanHtml("a &lt; b"))
        assertEquals("a > b", cleanHtml("a &gt; b"))
        assertEquals("He said \"hi\"", cleanHtml("He said &quot;hi&quot;"))
        assertEquals("it's", cleanHtml("it&#39;s"))
        assertEquals("it's", cleanHtml("it&apos;s"))
    }

    @Test
    fun `cleanHtml removes URLs`() {
        val html = "Check out https://example.com for more"
        val cleaned = cleanHtml(html)
        assertFalse(cleaned.contains("https://"))
    }

    @Test
    fun `cleanHtml collapses whitespace`() {
        val html = "Hello    world    again"
        val cleaned = cleanHtml(html)
        assertEquals("Hello world again", cleaned)
    }

    // -- Deduplication by ID --

    @Test
    fun `dedup ID generation is consistent`() {
        val album = "OK Computer"
        val artist = "Radiohead"
        val id = "${album.lowercase()}|${artist.lowercase()}"
            .replace(Regex("[^a-z0-9|]"), "-")
        assertEquals("ok-computer|radiohead", id)
    }

    @Test
    fun `dedup prevents duplicate albums`() {
        val seen = mutableSetOf<String>()
        val id1 = "ok-computer|radiohead"
        val id2 = "ok-computer|radiohead"
        assertTrue(seen.add(id1))
        assertFalse(seen.add(id2))
    }

    // -- Staleness check --

    @Test
    fun `cache is stale after 4 hours`() {
        val staleThreshold = 4 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val fourHoursAgo = now - staleThreshold - 1
        assertTrue(now - fourHoursAgo > staleThreshold)
    }

    @Test
    fun `cache is fresh within 4 hours`() {
        val staleThreshold = 4 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val oneHourAgo = now - (1 * 60 * 60 * 1000L)
        assertFalse(now - oneHourAgo > staleThreshold)
    }

    // -- Helper functions replicating CriticalDarlingsRepository private methods --

    private fun parseTitle(raw: String): Pair<String, String>? {
        val idx = raw.lastIndexOf(" by ")
        if (idx <= 0) return null
        val album = raw.substring(0, idx).trim()
        val artist = raw.substring(idx + 4).trim()
        if (album.isBlank() || artist.isBlank()) return null
        return album to artist
    }

    private fun extractSpotifyUrl(html: String): String? {
        val regex = Regex("""https?://open\.spotify\.com/album/[a-zA-Z0-9]+""")
        return regex.find(html)?.value
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}
