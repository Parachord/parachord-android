package com.parachord.android.data.db.dao

import com.parachord.android.data.db.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPlaylistSourceDaoTest {
    @Test
    fun `upsert stores and selectForLocal retrieves`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistSourceDao(db)
        dao.upsert(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "xyz",
            snapshotId = null,
            ownerId = "me",
            syncedAt = 1L,
        )
        val source = dao.selectForLocal("local-abc")
        assertNotNull(source)
        assertEquals("spotify", source!!.providerId)
        assertEquals("xyz", source.externalId)
        assertEquals("me", source.ownerId)
    }

    @Test
    fun `selectByExternalId returns the matching row`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistSourceDao(db)
        dao.upsert("local-abc", "spotify", "xyz", null, null, 1L)
        dao.upsert("local-def", "applemusic", "xyz", null, null, 2L)
        val match = dao.selectByExternalId("spotify", "xyz")
        assertNotNull(match)
        assertEquals("local-abc", match!!.localPlaylistId)
    }

    @Test
    fun `deleteForLocal removes the row`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistSourceDao(db)
        dao.upsert("local-abc", "spotify", "xyz", null, null, 1L)
        dao.deleteForLocal("local-abc")
        assertNull(dao.selectForLocal("local-abc"))
    }

    @Test
    fun `deleteForProvider nukes all rows for the provider`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistSourceDao(db)
        dao.upsert("a", "spotify", "1", null, null, 1L)
        dao.upsert("b", "spotify", "2", null, null, 2L)
        dao.upsert("c", "applemusic", "3", null, null, 3L)
        dao.deleteForProvider("spotify")
        assertNull(dao.selectForLocal("a"))
        assertNull(dao.selectForLocal("b"))
        assertNotNull(dao.selectForLocal("c"))
    }
}
