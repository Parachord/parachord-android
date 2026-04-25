package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-helper tests for Fix 3 of the multi-provider mirror-propagation
 * rules: the push loop in `SyncEngine.syncPlaylists` must skip a row
 * when (a) the row's pull source matches the current push target —
 * never re-push a pulled playlist back to its source — and (b) the
 * row's link to this provider has a `pendingAction` (e.g.
 * "remote-deleted") awaiting user resolution.
 *
 * Both predicates are pure DAO lookups; these tests pin them so the
 * call site in `SyncEngine` can rely on them.
 */
class PushLoopGuardTest {
    @Test
    fun `pull-source-matches guard skips Spotify push for Spotify-imported playlist`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        val pullSource = sourceDao.selectForLocal("spotify-abc")
        // Push target is Spotify. Pull source is Spotify. Skip.
        assertEquals("spotify", pullSource?.providerId)
    }

    @Test
    fun `pull-source-matches guard does NOT skip AM push for Spotify-imported playlist`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        val pullSource = sourceDao.selectForLocal("spotify-abc")
        // Push target is AM. Pull source is Spotify. Don't skip.
        assertEquals(false, pullSource?.providerId == "applemusic")
    }

    @Test
    fun `pull-source-matches guard never fires for local-only playlist`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        // No upsert — playlist has no syncedFrom row.
        assertNull(sourceDao.selectForLocal("local-abc"))
    }

    @Test
    fun `pendingAction skip fires when remote-deleted action is set`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val linkDao = SyncPlaylistLinkDao(db)
        linkDao.upsertWithSnapshot("local-abc", "spotify", "spx", null, 1L)
        linkDao.setPendingAction("local-abc", "spotify", "remote-deleted")
        val link = linkDao.selectForLink("local-abc", "spotify")
        assertEquals("remote-deleted", link?.pendingAction)
    }

    @Test
    fun `pendingAction skip does NOT fire when no action is set`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val linkDao = SyncPlaylistLinkDao(db)
        linkDao.upsertWithSnapshot("local-abc", "spotify", "spx", null, 1L)
        val link = linkDao.selectForLink("local-abc", "spotify")
        assertNull(link?.pendingAction)
    }
}
