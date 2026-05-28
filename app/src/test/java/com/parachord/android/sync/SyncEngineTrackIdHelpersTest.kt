package com.parachord.android.sync

import com.parachord.shared.model.PlaylistTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for the per-provider track-ID helpers in SyncEngine:
 *
 *   - `extractExternalTrackIds(tracks, providerId)`
 *   - `missingProviderId(track, providerId)`
 *   - `applyResolvedId(track, providerId, resolvedId)`
 *
 * All three helpers are `private` on SyncEngine; following the [PushCandidateTest]
 * precedent we pin intended semantics with inline copies of the `when` body. If
 * the production switch ever drifts from these tests, both must change in
 * lockstep (the per-provider rules are a cross-platform invariant — desktop
 * relies on the same dispatch).
 *
 * Phase 4 of the LB sync workstream (tasks 14-15) added a `listenbrainz` branch
 * to each: keyed on `PlaylistTrack.trackRecordingMbid` since LB playlists are
 * tracklists of MBIDs.
 */
class SyncEngineTrackIdHelpersTest {

    private fun track(
        spotifyUri: String? = null,
        appleMusicId: String? = null,
        recordingMbid: String? = null,
    ): PlaylistTrack = PlaylistTrack(
        playlistId = "p",
        position = 0,
        trackTitle = "Song",
        trackArtist = "Artist",
        trackSpotifyUri = spotifyUri,
        trackAppleMusicId = appleMusicId,
        trackRecordingMbid = recordingMbid,
    )

    /** Inline copy of [SyncEngine.extractExternalTrackIds]. */
    private fun extractExternalTrackIds(
        tracks: List<PlaylistTrack>,
        providerId: String,
    ): List<String> = when (providerId) {
        "spotify" -> tracks.mapNotNull { it.trackSpotifyUri }
        "applemusic" -> tracks.mapNotNull { it.trackAppleMusicId }
        "listenbrainz" -> tracks.mapNotNull { it.trackRecordingMbid }
        else -> emptyList()
    }

    /** Inline copy of [SyncEngine.missingProviderId]. */
    private fun missingProviderId(track: PlaylistTrack, providerId: String): Boolean = when (providerId) {
        "spotify" -> track.trackSpotifyUri.isNullOrBlank()
        "applemusic" -> track.trackAppleMusicId.isNullOrBlank()
        "listenbrainz" -> track.trackRecordingMbid.isNullOrBlank()
        else -> true
    }

    /** Inline copy of [SyncEngine.applyResolvedId]. */
    private fun applyResolvedId(
        track: PlaylistTrack,
        providerId: String,
        resolvedId: String,
    ): PlaylistTrack = when (providerId) {
        "spotify" -> {
            val bareId = resolvedId.removePrefix("spotify:track:")
            track.copy(
                trackSpotifyUri = resolvedId,
                trackSpotifyId = bareId.takeIf { it.isNotBlank() } ?: track.trackSpotifyId,
            )
        }
        "applemusic" -> track.copy(trackAppleMusicId = resolvedId)
        "listenbrainz" -> track.copy(trackRecordingMbid = resolvedId)
        else -> track
    }

    // -- extractExternalTrackIds --

    @Test fun `extract Spotify URIs filters tracks without one`() {
        val tracks = listOf(
            track(spotifyUri = "spotify:track:A"),
            track(spotifyUri = null),
            track(spotifyUri = "spotify:track:B"),
        )
        assertEquals(listOf("spotify:track:A", "spotify:track:B"), extractExternalTrackIds(tracks, "spotify"))
    }

    @Test fun `extract Apple Music IDs filters tracks without one`() {
        val tracks = listOf(
            track(appleMusicId = "12345"),
            track(appleMusicId = null),
        )
        assertEquals(listOf("12345"), extractExternalTrackIds(tracks, "applemusic"))
    }

    @Test fun `extract ListenBrainz MBIDs filters tracks without one`() {
        val tracks = listOf(
            track(recordingMbid = "mbid-1"),
            track(recordingMbid = null),
            track(recordingMbid = "mbid-2"),
            track(recordingMbid = ""), // blank still kept by mapNotNull (only null skipped)
        )
        // mapNotNull skips only nulls — empty string passes through. Production
        // helper documents "tracks without the relevant ID are silently
        // skipped" which means null-only; downstream LB API will reject empty
        // string. Hydration step is responsible for filling these in.
        assertEquals(listOf("mbid-1", "mbid-2", ""), extractExternalTrackIds(tracks, "listenbrainz"))
    }

    @Test fun `extract for unknown provider returns empty`() {
        assertTrue(extractExternalTrackIds(listOf(track(spotifyUri = "x")), "tidal").isEmpty())
    }

    // -- missingProviderId --

    @Test fun `missing for Spotify is true when URI is null or blank`() {
        assertTrue(missingProviderId(track(spotifyUri = null), "spotify"))
        assertTrue(missingProviderId(track(spotifyUri = ""), "spotify"))
        assertFalse(missingProviderId(track(spotifyUri = "spotify:track:X"), "spotify"))
    }

    @Test fun `missing for Apple Music is true when ID is null or blank`() {
        assertTrue(missingProviderId(track(appleMusicId = null), "applemusic"))
        assertTrue(missingProviderId(track(appleMusicId = ""), "applemusic"))
        assertFalse(missingProviderId(track(appleMusicId = "123"), "applemusic"))
    }

    @Test fun `missing for ListenBrainz is true when MBID is null or blank`() {
        assertTrue(missingProviderId(track(recordingMbid = null), "listenbrainz"))
        assertTrue(missingProviderId(track(recordingMbid = ""), "listenbrainz"))
        assertFalse(missingProviderId(track(recordingMbid = "abc-mbid"), "listenbrainz"))
    }

    @Test fun `missing for unknown provider always true`() {
        // Defensive default — an unknown provider's tracks are by definition
        // missing whatever ID would be required, so hydration would attempt
        // a search for every track (which then returns nothing). Better than
        // silently pushing without IDs.
        assertTrue(missingProviderId(track(spotifyUri = "x", appleMusicId = "y", recordingMbid = "z"), "tidal"))
    }

    // -- applyResolvedId --

    @Test fun `apply Spotify URI also derives bare ID`() {
        val t = applyResolvedId(track(), "spotify", "spotify:track:XYZ")
        assertEquals("spotify:track:XYZ", t.trackSpotifyUri)
        assertEquals("XYZ", t.trackSpotifyId)
    }

    @Test fun `apply Apple Music ID writes only that field`() {
        val t = applyResolvedId(track(), "applemusic", "999")
        assertEquals("999", t.trackAppleMusicId)
        assertEquals(null, t.trackSpotifyUri)
        assertEquals(null, t.trackRecordingMbid)
    }

    @Test fun `apply ListenBrainz MBID writes recording MBID only`() {
        val t = applyResolvedId(track(), "listenbrainz", "abc-1234")
        assertEquals("abc-1234", t.trackRecordingMbid)
        assertEquals(null, t.trackSpotifyUri)
        assertEquals(null, t.trackAppleMusicId)
    }

    @Test fun `apply for unknown provider returns track unchanged`() {
        val original = track(spotifyUri = "spotify:track:A")
        val result = applyResolvedId(original, "tidal", "ignored")
        assertEquals(original, result)
    }
}
