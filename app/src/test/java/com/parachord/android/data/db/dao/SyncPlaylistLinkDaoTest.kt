package com.parachord.android.data.db.dao

import com.parachord.android.data.db.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPlaylistLinkDaoTest {
    @Test
    fun `upsertWithSnapshot stores and retrieves snapshotId`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "xyz",
            snapshotId = "snap-1",
            syncedAt = 1L,
        )
        assertEquals("snap-1", dao.selectForLink("local-abc", "spotify")?.snapshotId)
    }

    @Test
    fun `setPendingAction and clearPendingAction round-trip`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("a", "spotify", "x", null, 1L)
        dao.setPendingAction("a", "spotify", "remote-deleted")
        assertEquals("remote-deleted", dao.selectForLink("a", "spotify")?.pendingAction)
        dao.clearPendingAction("a", "spotify")
        assertNull(dao.selectForLink("a", "spotify")?.pendingAction)
    }

    @Test
    fun `selectPendingForProvider returns only rows with pendingAction set`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("a", "spotify", "x", null, 1L)
        dao.upsertWithSnapshot("b", "spotify", "y", null, 2L)
        dao.upsertWithSnapshot("c", "applemusic", "z", null, 3L)
        dao.setPendingAction("b", "spotify", "remote-deleted")
        dao.setPendingAction("c", "applemusic", "remote-deleted")
        val pendingSpotify = dao.selectPendingForProvider("spotify")
        assertEquals(1, pendingSpotify.size)
        assertEquals("b", pendingSpotify.first().localPlaylistId)
    }
}
