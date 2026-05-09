package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Recording resolver — captures which methods get called in which order
 * and returns scripted results.
 */
private class RecordingResolver(
    var mbidResult: ResolvedProtocolPlay? = null,
    var spotifyResult: ResolvedProtocolPlay? = null,
    var appleMusicResult: ResolvedProtocolPlay? = null,
    var urlResult: ResolvedProtocolPlay? = null,
    var artistTitleResult: ResolvedProtocolPlay? = null,
) : ProtocolInputResolver {
    val calls = mutableListOf<String>()
    override suspend fun resolveByMbid(mbid: String): ResolvedProtocolPlay? { calls += "mbid($mbid)"; return mbidResult }
    override suspend fun resolveBySpotify(spotifyIdOrUri: String): ResolvedProtocolPlay? { calls += "spotify($spotifyIdOrUri)"; return spotifyResult }
    override suspend fun resolveByAppleMusic(appleMusicId: String): ResolvedProtocolPlay? { calls += "applemusic($appleMusicId)"; return appleMusicResult }
    override suspend fun resolveByUrl(url: String): ResolvedProtocolPlay? { calls += "url($url)"; return urlResult }
    override suspend fun resolveByArtistTitle(artist: String, title: String?, album: String?): ResolvedProtocolPlay? { calls += "artistTitle($artist,$title)"; return artistTitleResult }
}

class ProtocolInputResolverTest {

    private val ok = ResolvedProtocolPlay("ok", listOf(ProtocolTrack("A", "T")))
    private val canonical = "c70a2d8f-c1b7-4f10-9d10-95158a08b528"

    @Test
    fun mbidWinsWhenAllAllowed() = runTest {
        val r = RecordingResolver(mbidResult = ok, spotifyResult = ok, appleMusicResult = ok)
        val input = ProtocolPlayInput(mbid = canonical, spotify = "spotify:album:abc", applemusic = "12345")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        assertEquals(listOf("mbid($canonical)"), r.calls)
    }

    @Test
    fun spotifyWinsAfterMbidFallthrough() = runTest {
        val r = RecordingResolver(mbidResult = null, spotifyResult = ok, appleMusicResult = ok)
        val input = ProtocolPlayInput(mbid = canonical, spotify = "spotify:album:abc", applemusic = "12345")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        assertEquals(listOf("mbid($canonical)", "spotify(spotify:album:abc)"), r.calls)
    }

    @Test
    fun appleMusicWinsAfterMbidAndSpotifyFallthrough() = runTest {
        val r = RecordingResolver(appleMusicResult = ok)
        val input = ProtocolPlayInput(mbid = canonical, spotify = "spotify:album:abc", applemusic = "12345")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        assertEquals(listOf("mbid($canonical)", "spotify(spotify:album:abc)", "applemusic(12345)"), r.calls)
    }

    @Test
    fun urlWinsBeforeTracksAndArtistTitle() = runTest {
        val r = RecordingResolver(urlResult = ok)
        val input = ProtocolPlayInput(
            url = "https://example.com/x.xspf",
            tracks = listOf(ProtocolTrack("A", "T")),
            artist = "A",
            title = "T",
        )
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        assertEquals(listOf("url(https://example.com/x.xspf)"), r.calls)
    }

    @Test
    fun inlineTracksWrappedDirectlyWithoutResolverCall() = runTest {
        val r = RecordingResolver()
        val tracks = listOf(ProtocolTrack("A", "T1"), ProtocolTrack("B", "T2"))
        val input = ProtocolPlayInput(tracks = tracks, title = "Inline Mix")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals("Inline Mix", out!!.displayName)
        assertEquals(2, out.tracks.size)
        assertTrue(r.calls.isEmpty())  // resolver never called for inline tracks
    }

    @Test
    fun artistTitleLastResort() = runTest {
        val r = RecordingResolver(artistTitleResult = ok)
        val input = ProtocolPlayInput(artist = "Witch Post", title = "Twin Fawn")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        assertEquals(listOf("artistTitle(Witch Post,Twin Fawn)"), r.calls)
    }

    @Test
    fun returnsNullWhenNothingResolves() = runTest {
        val r = RecordingResolver()  // every method returns null
        val input = ProtocolPlayInput(mbid = canonical, spotify = "x", applemusic = "y", artist = "A", title = "T")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertNull(out)
        // All slots tried (proves we walked the full priority chain).
        assertEquals(4, r.calls.size)
    }

    // ── Per-command gating ──

    @Test
    fun gatedMbidIsSkippedSilently() = runTest {
        // play/playlist disallows mbid; should fall through to spotify.
        val r = RecordingResolver(spotifyResult = ok)
        val input = ProtocolPlayInput(mbid = canonical, spotify = "spotify:playlist:xyz")
        val opts = ProtocolResolveOptions(allowMbid = false)
        val out = resolveProtocolPlayInput(input, opts, r)
        assertEquals(ok, out)
        assertEquals(listOf("spotify(spotify:playlist:xyz)"), r.calls)
        assertFalse(r.calls.any { it.startsWith("mbid") })
    }

    @Test
    fun gatedProviderIdSkipsBothSpotifyAndAppleMusic() = runTest {
        // play/radio (Mode B) disallows providers but allows artist+title.
        val r = RecordingResolver(artistTitleResult = ok)
        val input = ProtocolPlayInput(spotify = "x", applemusic = "y", artist = "A", title = "T")
        val opts = ProtocolResolveOptions(
            allowMbid = false, allowProviderId = false, allowUrl = false, allowTracks = false,
        )
        val out = resolveProtocolPlayInput(input, opts, r)
        assertEquals(ok, out)
        assertEquals(listOf("artistTitle(A,T)"), r.calls)
    }

    @Test
    fun rejectsMalformedMbidSilently() = runTest {
        // MBID field present but doesn't pass strict validation — fall through.
        val r = RecordingResolver(spotifyResult = ok)
        val input = ProtocolPlayInput(mbid = "NOT-A-VALID-MBID", spotify = "spotify:album:abc")
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        assertFalse(r.calls.any { it.startsWith("mbid") })
    }

    @Test
    fun acceptsUppercaseMbidViaLowercasedNormalization() = runTest {
        val r = RecordingResolver(mbidResult = ok)
        val input = ProtocolPlayInput(mbid = canonical.uppercase())
        val out = resolveProtocolPlayInput(input, ProtocolResolveOptions(), r)
        assertEquals(ok, out)
        // Normalized to lowercase before passing to resolver.
        assertEquals(listOf("mbid($canonical)"), r.calls)
    }
}
