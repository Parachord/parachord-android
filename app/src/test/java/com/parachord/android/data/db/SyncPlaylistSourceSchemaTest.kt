package com.parachord.android.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaylistSourceSchemaTest {
    @Test
    fun `upsert and read syncedFrom`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistSourceQueries.upsert(
            localPlaylistId = "spotify-abc",
            providerId = "spotify",
            externalId = "abc",
            snapshotId = "snap-1",
            ownerId = "user-1",
            syncedAt = 1000L,
        )
        val row = db.syncPlaylistSourceQueries.selectForLocal("spotify-abc").executeAsOne()
        assertEquals("spotify", row.providerId)
        assertEquals("abc", row.externalId)
        assertEquals("user-1", row.ownerId)
    }

    @Test
    fun `primary key is localPlaylistId alone — one source per playlist`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistSourceQueries.upsert("p1", "spotify", "a", null, null, 1L)
        db.syncPlaylistSourceQueries.upsert("p1", "applemusic", "b", null, null, 2L)
        val row = db.syncPlaylistSourceQueries.selectForLocal("p1").executeAsOne()
        assertEquals("applemusic", row.providerId)  // second upsert wins
    }
}
