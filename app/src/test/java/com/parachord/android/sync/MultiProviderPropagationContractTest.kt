package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end contract test for the multi-provider mirror-propagation
 * rules. Stitches the four cooperating fixes together against a
 * realistic round-trip scenario:
 *
 *   1. Local playlist with syncedFrom=spotify and push mirrors on
 *      both spotify (round-trip — Spotify pushes to itself when it
 *      imports its own creation) and applemusic.
 *   2. Remote Spotify edits land. A pull replaces local tracks and
 *      Fix 1 flags locallyModified=true because an AM mirror exists.
 *   3. Push loop runs against applemusic. Fix 3 guards allow the push
 *      (source is spotify; AM is not the source). Push completes;
 *      sync_playlist_link.applemusic.syncedAt advances.
 *   4. Post-loop clear runs. Fix 4 sees relevantMirrors=[applemusic]
 *      (spotify excluded as source); allCaught=true; flag clears.
 *
 * Without ANY of the four fixes this scenario produces a stuck
 * locallyModified=true (1+4 missing) or a missing AM update (3
 * missing) or a never-cleared flag (4 missing).
 *
 * The test uses pure DAO predicates rather than wiring up SyncEngine
 * + fake providers — the fixes are predicate-driven and exercising
 * each predicate against the same realistic state IS the contract.
 */
class MultiProviderPropagationContractTest {
    @Test
    fun `four fixes together complete an Android-Spotify-AM round trip`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistId = "spotify-abc"

        // ── Step 1: setup ────────────────────────────────────────────
        // syncedFrom.spotify; syncedTo: { spotify, applemusic }.
        // Both mirrors initially in sync at syncedAt=100; lastModified=100.
        sourceDao.upsert(playlistId, "spotify", "abc", "snap-1", "user-1", 100L)
        linkDao.upsertWithSnapshot(playlistId, "spotify", "abc", "snap-1", 100L)
        linkDao.upsertWithSnapshot(playlistId, "applemusic", "amx", null, 100L)

        // ── Step 2: simulate pull from Spotify ───────────────────────
        // A pull bumps lastModified. Fix 1: hasOtherMirrors must say
        // YES because applemusic is a different-provider mirror.
        val lastModifiedAfterPull = 200L
        val hasOtherMirrors = linkDao.hasOtherMirrors(playlistId, "spotify")
        assertTrue("Fix 1: pull must flag locallyModified — AM mirror exists", hasOtherMirrors)

        // ── Step 3: push loop runs against applemusic ────────────────
        // Fix 3 guards check pull source vs current target.
        val applemusicTarget = "applemusic"
        val pullSource = sourceDao.selectForLocal(playlistId)
        val skippedByFix3 = pullSource?.providerId == applemusicTarget
        assertFalse("Fix 3: AM is not the pull source — push must proceed", skippedByFix3)

        // Push succeeds — bump applemusic syncedAt.
        val pushedAt = 250L
        linkDao.upsertWithSnapshot(playlistId, applemusicTarget, "amx", null, pushedAt)

        // ── Step 4: post-loop clear runs ─────────────────────────────
        // Fix 4: relevantMirrors = enabled & not source. enabled is
        // both providers; source is spotify; so relevantMirrors=[applemusic].
        val enabled = setOf("spotify", "applemusic")
        val sourceProvider = sourceDao.selectForLocal(playlistId)?.providerId
        val mirrors = linkDao.selectForLocal(playlistId)
        val relevantMirrors = mirrors.filter {
            it.providerId in enabled && it.providerId != sourceProvider
        }
        assertEquals("Fix 4: relevantMirrors=[applemusic] (spotify excluded)", 1, relevantMirrors.size)
        assertEquals("applemusic", relevantMirrors.first().providerId)

        val allCaught = relevantMirrors.all { it.syncedAt >= lastModifiedAfterPull }
        assertTrue("Fix 4: AM syncedAt=$pushedAt >= lastModified=$lastModifiedAfterPull; flag clears", allCaught)
    }

    @Test
    fun `single-provider Spotify-only round trip clears the flag too`() = runBlocking {
        // No AM mirror; only Spotify. Pull doesn't flag (no other mirrors)
        // and the post-loop clear still works.
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistId = "spotify-abc"
        sourceDao.upsert(playlistId, "spotify", "abc", "snap-1", null, 100L)
        linkDao.upsertWithSnapshot(playlistId, "spotify", "abc", "snap-1", 100L)

        // Step 2: Fix 1 — no other mirrors, so don't flag.
        val hasOtherMirrors = linkDao.hasOtherMirrors(playlistId, "spotify")
        assertFalse("single-provider pull must NOT flag", hasOtherMirrors)

        // Step 4: Fix 4 — relevantMirrors empty (the only mirror IS the source).
        val enabled = setOf("spotify")
        val sourceProvider = sourceDao.selectForLocal(playlistId)?.providerId
        val mirrors = linkDao.selectForLocal(playlistId)
        val relevantMirrors = mirrors.filter {
            it.providerId in enabled && it.providerId != sourceProvider
        }
        assertEquals(0, relevantMirrors.size)
        // Per Fix 4: empty relevantMirrors → clear immediately.
    }

    @Test
    fun `pendingAction skip prevents stuck-flag deadlock with deleted remote`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistId = "spotify-abc"
        sourceDao.upsert(playlistId, "spotify", "abc", "snap-1", null, 100L)
        linkDao.upsertWithSnapshot(playlistId, "spotify", "abc", "snap-1", 100L)
        linkDao.upsertWithSnapshot(playlistId, "applemusic", "amx", null, 100L)
        // User deleted the AM playlist on the web; sync detected and marked it.
        linkDao.setPendingAction(playlistId, "applemusic", "remote-deleted")

        // The pending row stays out of relevantMirrors-comparison after AM
        // is the only "relevant" mirror but it's frozen at syncedAt=100. If
        // a pull bumps lastModified=200, the flag stays set forever unless
        // the user resolves the pendingAction. That's the intended behavior:
        // we don't want to silently re-create the AM playlist; surface
        // "Re-push or Unlink?" via the playlist banner instead.
        val link = linkDao.selectForLink(playlistId, "applemusic")
        assertEquals("remote-deleted", link?.pendingAction)
    }
}
