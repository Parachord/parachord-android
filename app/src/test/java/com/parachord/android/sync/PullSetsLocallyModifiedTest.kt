package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper test for Fix 1 of the multi-provider mirror-propagation
 * rules: a pull from one provider should set `locallyModified = true`
 * iff the playlist has at least one push mirror under a DIFFERENT
 * provider. Without this, an Android-edit → Spotify → desktop pull
 * stops at the desktop and never reaches Apple Music.
 *
 * The actual call site (PlaylistDetailViewModel.pullRemoteChanges /
 * SyncEngine.pullPlaylist) reads this predicate via
 * `syncPlaylistLinkDao.hasOtherMirrors(playlistId, currentProviderId)`
 * — these tests pin the predicate's behavior at the DAO level so that
 * the higher-level call sites can rely on it without re-deriving the
 * SQL each time.
 */
class PullSetsLocallyModifiedTest {
    @Test
    fun `pull from spotify flags locallyModified when applemusic mirror exists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("p1", "spotify", "spx", null, 1L)
        dao.upsertWithSnapshot("p1", "applemusic", "amx", null, 2L)
        assertTrue(
            "AM mirror exists; pull from Spotify must flag locallyModified so AM gets the update",
            dao.hasOtherMirrors("p1", "spotify"),
        )
    }

    @Test
    fun `pull from spotify does NOT flag locallyModified when only spotify mirror exists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("p1", "spotify", "spx", null, 1L)
        assertFalse(
            "Spotify is the only mirror — no other provider needs propagation",
            dao.hasOtherMirrors("p1", "spotify"),
        )
    }

    @Test
    fun `pull from applemusic flags locallyModified when spotify mirror exists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("p1", "spotify", "spx", null, 1L)
        dao.upsertWithSnapshot("p1", "applemusic", "amx", null, 2L)
        assertTrue(
            "Symmetric: pull from AM must also flag for Spotify propagation",
            dao.hasOtherMirrors("p1", "applemusic"),
        )
    }

    @Test
    fun `playlist with no mirrors at all returns false`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        assertFalse(dao.hasOtherMirrors("orphan", "spotify"))
    }
}
