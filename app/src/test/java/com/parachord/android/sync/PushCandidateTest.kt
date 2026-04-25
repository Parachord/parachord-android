package com.parachord.android.sync

import com.parachord.android.data.db.entity.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for the per-provider push-candidate predicate
 * extracted in Phase 4.5. The predicate determines which playlists a
 * given provider's push loop iterates.
 *
 * Spotify keeps its legacy filter (local-* + hosted XSPF, only when
 * not already linked via spotifyId scalar). Apple Music's filter
 * additionally includes Spotify-imported playlists, since the Phase 3
 * `syncedFrom` guard skips a Spotify-imported playlist when targeting
 * Spotify but NOT when targeting Apple Music — exactly the behavior
 * the AM filter must allow.
 *
 * The predicate body is private to SyncEngine; these tests pin its
 * intended semantics by reproducing the predicate inline.
 */
class PushCandidateTest {

    private fun playlist(
        id: String,
        spotifyId: String? = null,
        sourceUrl: String? = null,
        name: String = "My List",
    ): PlaylistEntity = PlaylistEntity(
        id = id,
        name = name,
        description = null,
        artworkUrl = null,
        trackCount = 0,
        createdAt = 0L,
        updatedAt = 0L,
        spotifyId = spotifyId,
        snapshotId = null,
        lastModified = 0L,
        locallyModified = false,
        ownerName = null,
        sourceUrl = sourceUrl,
        sourceContentHash = null,
        localOnly = false,
    )

    /** Inline copy of [SyncEngine.isPushCandidate]. */
    private fun isPushCandidate(p: PlaylistEntity, providerId: String): Boolean {
        val baseEligible = p.id.startsWith("local-") || p.sourceUrl != null
        return when (providerId) {
            "spotify" -> p.spotifyId == null && baseEligible
            "applemusic" -> baseEligible || p.id.startsWith("spotify-")
            else -> baseEligible
        }
    }

    @Test fun `Spotify pushes local-prefix playlists with no spotifyId`() {
        assertTrue(isPushCandidate(playlist("local-abc"), "spotify"))
    }

    @Test fun `Spotify pushes hosted XSPF playlists with no spotifyId`() {
        assertTrue(isPushCandidate(playlist("hosted-xyz", sourceUrl = "https://e.xspf"), "spotify"))
    }

    @Test fun `Spotify does NOT push playlists already linked to Spotify`() {
        assertFalse(isPushCandidate(playlist("local-abc", spotifyId = "spx"), "spotify"))
    }

    @Test fun `Spotify does NOT push Spotify-imported playlists`() {
        assertFalse(isPushCandidate(playlist("spotify-abc", spotifyId = "abc"), "spotify"))
    }

    @Test fun `Spotify does NOT push AM-imported playlists`() {
        assertFalse(isPushCandidate(playlist("applemusic-abc"), "spotify"))
    }

    @Test fun `Apple Music pushes local-prefix playlists`() {
        assertTrue(isPushCandidate(playlist("local-abc"), "applemusic"))
    }

    @Test fun `Apple Music pushes hosted XSPF playlists`() {
        assertTrue(isPushCandidate(playlist("hosted-xyz", sourceUrl = "https://e.xspf"), "applemusic"))
    }

    @Test fun `Apple Music ALSO pushes Spotify-imported playlists`() {
        // The whole point of Phase 3's syncedFrom guard — a Spotify-
        // imported playlist mirrors to AM as a downstream push.
        assertTrue(isPushCandidate(playlist("spotify-abc", spotifyId = "abc"), "applemusic"))
    }

    @Test fun `Apple Music does NOT push AM-imported playlists`() {
        // The runtime syncedFrom guard skips this case (the Phase 3 guard
        // sees pull-source==current-target and skips). Even if it didn't,
        // the candidate filter excludes id-prefix that matches the
        // current provider's import convention. AM-import IDs start with
        // applemusic- and aren't local-* or hosted, so the filter
        // returns false for them under AM push too.
        assertFalse(isPushCandidate(playlist("applemusic-abc"), "applemusic"))
    }

    @Test fun `unknown provider falls back to baseline (local + hosted)`() {
        // Defensive — until a third provider lands, the default branch
        // still treats local-* and hosted as eligible so a misregistered
        // provider doesn't silently no-op the entire push.
        assertTrue(isPushCandidate(playlist("local-abc"), "tidal"))
        assertFalse(isPushCandidate(playlist("spotify-abc"), "tidal"))
    }
}
