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
 * Pure-helper tests for Fix 2 of the multi-provider mirror-propagation
 * rules: local-content mutators in `LibraryRepository`
 * (`addTracksToPlaylist`, `removeTrackFromPlaylist`,
 * `reorderPlaylistTracks`, `renamePlaylist`) should flag
 * `locallyModified = true` only when the playlist has sync intent —
 * either a `syncedFrom` pull source OR at least one `syncedTo` push
 * mirror.
 *
 * Editing a local-only playlist (no source, no mirrors) must NOT
 * flag the row, because a never-syncable playlist would otherwise
 * waste push-loop iterations forever.
 *
 * The `LibraryRepository.hasSyncIntent` helper is private; these
 * tests pin its DAO-level building blocks so the repository call
 * sites can rely on them.
 */
class MutatorFlagGuardTest {
    @Test
    fun `local-only playlist has no sync intent`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        // No source, no link.
        val source = sourceDao.selectForLocal("local-abc")
        val links = linkDao.selectForLocal("local-abc")
        val hasIntent = source != null || links.isNotEmpty()
        assertFalse("local-only playlist should not flag locallyModified on edit", hasIntent)
    }

    @Test
    fun `playlist with syncedFrom has sync intent`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        val source = sourceDao.selectForLocal("spotify-abc")
        val links = linkDao.selectForLocal("spotify-abc")
        val hasIntent = source != null || links.isNotEmpty()
        assertTrue("synced-from playlist must flag locallyModified on edit", hasIntent)
    }

    @Test
    fun `playlist with only a syncedTo push mirror has sync intent`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        // Local-created playlist, never imported, but pushed to Spotify.
        linkDao.upsertWithSnapshot("local-pushed", "spotify", "spx", null, 1L)
        val source = sourceDao.selectForLocal("local-pushed")
        val links = linkDao.selectForLocal("local-pushed")
        val hasIntent = source != null || links.isNotEmpty()
        assertTrue("push-target playlist must flag locallyModified on edit", hasIntent)
    }

    @Test
    fun `multiple mirrors and a source still resolves to has-intent`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        linkDao.upsertWithSnapshot("spotify-abc", "spotify", "abc", null, 2L)
        linkDao.upsertWithSnapshot("spotify-abc", "applemusic", "amx", null, 3L)
        val source = sourceDao.selectForLocal("spotify-abc")
        val links = linkDao.selectForLocal("spotify-abc")
        val hasIntent = source != null || links.isNotEmpty()
        assertTrue(hasIntent)
        assertEquals(2, links.size)
    }
}
