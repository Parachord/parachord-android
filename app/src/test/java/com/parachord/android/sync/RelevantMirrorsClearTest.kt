package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for Fix 4 of the multi-provider mirror-propagation
 * rules: after the push loop runs, `locallyModified` should clear iff
 * every "relevant" mirror is caught up. Relevant mirrors are enabled
 * providers with a `sync_playlist_link` row for this playlist
 * EXCLUDING the pull-source provider (Fix 3's guard never pushes
 * back to source, so its syncedAt would never advance).
 *
 * The actual call site is `SyncEngine.clearLocallyModifiedFlags`. These
 * tests pin the relevant-mirrors set composition at the DAO level.
 */
class RelevantMirrorsClearTest {
    @Test
    fun `relevantMirrors excludes the source provider`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        linkDao.upsertWithSnapshot("spotify-abc", "spotify", "abc", null, 200L)
        linkDao.upsertWithSnapshot("spotify-abc", "applemusic", "amx", null, 200L)
        val sourceProvider = sourceDao.selectForLocal("spotify-abc")?.providerId
        val enabledProviders = setOf("spotify", "applemusic")
        val all = linkDao.selectForLocal("spotify-abc")
        val relevant = all.filter { it.providerId in enabledProviders && it.providerId != sourceProvider }
        assertEquals(1, relevant.size)
        assertEquals("applemusic", relevant.first().providerId)
    }

    @Test
    fun `flag clears when all relevant mirrors caught up`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        linkDao.upsertWithSnapshot("spotify-abc", "spotify", "abc", null, 200L)
        linkDao.upsertWithSnapshot("spotify-abc", "applemusic", "amx", null, 200L)
        val lastModified = 100L
        val sourceProvider = sourceDao.selectForLocal("spotify-abc")?.providerId
        val enabled = setOf("spotify", "applemusic")
        val mirrors = linkDao.selectForLocal("spotify-abc")
        val relevant = mirrors.filter { it.providerId in enabled && it.providerId != sourceProvider }
        val allCaught = relevant.all { it.syncedAt >= lastModified }
        assertTrue("AM mirror at syncedAt=200 >= lastModified=100; flag should clear", allCaught)
    }

    @Test
    fun `flag stays set when one relevant mirror is behind`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        linkDao.upsertWithSnapshot("spotify-abc", "spotify", "abc", null, 200L)
        linkDao.upsertWithSnapshot("spotify-abc", "applemusic", "amx", null, 50L)
        val lastModified = 100L
        val sourceProvider = sourceDao.selectForLocal("spotify-abc")?.providerId
        val enabled = setOf("spotify", "applemusic")
        val mirrors = linkDao.selectForLocal("spotify-abc")
        val relevant = mirrors.filter { it.providerId in enabled && it.providerId != sourceProvider }
        val allCaught = relevant.all { it.syncedAt >= lastModified }
        assertEquals(false, allCaught)
    }

    @Test
    fun `empty relevantMirrors clears flag immediately (source-only playlist)`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        // Spotify-imported playlist with only a Spotify link (which equals the source provider).
        sourceDao.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
        linkDao.upsertWithSnapshot("spotify-abc", "spotify", "abc", null, 200L)
        val sourceProvider = sourceDao.selectForLocal("spotify-abc")?.providerId
        val enabled = setOf("spotify")
        val mirrors = linkDao.selectForLocal("spotify-abc")
        val relevant = mirrors.filter { it.providerId in enabled && it.providerId != sourceProvider }
        assertEquals(0, relevant.size)
        // Per Fix 4, empty relevant set → clear flag immediately.
    }

    @Test
    fun `mirrors not in enabledProviders are not relevant`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val linkDao = SyncPlaylistLinkDao(db)
        // Tidal mirror exists but Tidal is not enabled.
        linkDao.upsertWithSnapshot("p1", "spotify", "spx", null, 200L)
        linkDao.upsertWithSnapshot("p1", "tidal", "tx", null, 50L)
        val enabled = setOf("spotify")
        val mirrors = linkDao.selectForLocal("p1")
        val relevant = mirrors.filter { it.providerId in enabled }
        assertEquals(1, relevant.size)
        assertEquals("spotify", relevant.first().providerId)
    }
}
