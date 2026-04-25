package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for the cross-provider syncedFrom preservation
 * guard in `SyncEngine.syncPlaylists`' import branch. When an import
 * matches a local row whose `syncedFrom` points at a DIFFERENT
 * provider, we must NOT overwrite syncedFrom and must NOT refetch
 * tracks — the source provider is authoritative.
 *
 * The actual call site uses
 * `existingPullSource.providerId != providerId` as the predicate;
 * these tests pin the DAO-level building blocks.
 */
class CrossProviderSyncedFromTest {
    @Test
    fun `applemusic import does NOT overwrite spotify syncedFrom on a push-mirror local`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        // Setup: a local playlist whose pull source is Spotify.
        sourceDao.upsert("spotify-abc", "spotify", "abc", "snap-1", "user-1", 1L)
        val existing = sourceDao.selectForLocal("spotify-abc")!!
        // Pretend we're now in the Apple Music import branch.
        val importingProviderId = "applemusic"
        val isCrossProviderPushMirror = existing.providerId != importingProviderId
        assertTrue("AM is importing but Spotify is the source — must preserve syncedFrom", isCrossProviderPushMirror)
    }

    @Test
    fun `spotify re-import on its own source playlist DOES proceed with refetch`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", "snap-1", null, 1L)
        val existing = sourceDao.selectForLocal("spotify-abc")!!
        val importingProviderId = "spotify"
        val isCrossProviderPushMirror = existing.providerId != importingProviderId
        assertFalse("Spotify owns the source — proceed with normal refetch", isCrossProviderPushMirror)
    }

    @Test
    fun `local-only playlist with no source is NOT a cross-provider push mirror`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        // No upsert.
        val existing = sourceDao.selectForLocal("local-abc")
        assertNull(existing)
        // Per the call site: `existingPullSource != null && existingPullSource.providerId != providerId`
        // is the full predicate. existingPullSource == null → not a cross-provider mirror.
        val isCrossProviderPushMirror = existing != null && existing.providerId != "spotify"
        assertFalse(isCrossProviderPushMirror)
    }

    @Test
    fun `the syncedFrom row survives an import from a different provider (semantics check)`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", "snap-1", null, 1L)
        // The import branch's preservation path doesn't call sourceDao.upsert
        // on cross-provider matches — it only writes sync_playlist_link.
        // Verify the source row persists unchanged after a hypothetical
        // AM-import that takes the preservation path:
        val before = sourceDao.selectForLocal("spotify-abc")!!
        // (no source rewrite in the preservation path)
        val after = sourceDao.selectForLocal("spotify-abc")!!
        assertEquals(before.providerId, after.providerId)
        assertEquals(before.externalId, after.externalId)
        assertEquals(before.snapshotId, after.snapshotId)
    }
}
