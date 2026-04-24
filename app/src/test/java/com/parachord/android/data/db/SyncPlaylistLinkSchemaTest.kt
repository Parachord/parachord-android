package com.parachord.android.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaylistLinkSchemaTest {
    @Test
    fun `snapshotId persists across upsert`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "spot-xyz",
            snapshotId = "snap-1",
            syncedAt = 1000L,
        )
        val row = db.syncPlaylistLinkQueries.selectForLink("local-abc", "spotify").executeAsOne()
        assertEquals("snap-1", row.snapshotId)
    }

    @Test
    fun `pendingAction round-trips`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "spot-xyz",
            snapshotId = null,
            syncedAt = 1000L,
        )
        db.syncPlaylistLinkQueries.setPendingAction("remote-deleted", "local-abc", "spotify")
        val row = db.syncPlaylistLinkQueries.selectForLink("local-abc", "spotify").executeAsOne()
        assertEquals("remote-deleted", row.pendingAction)
    }

    @Test
    fun `selectPendingForProvider returns only rows with non-null pendingAction`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot("a", "spotify", "x", null, 1L)
        db.syncPlaylistLinkQueries.upsertWithSnapshot("b", "spotify", "y", null, 2L)
        db.syncPlaylistLinkQueries.setPendingAction("remote-deleted", "b", "spotify")
        val pending = db.syncPlaylistLinkQueries.selectPendingForProvider("spotify").executeAsList()
        assertEquals(1, pending.size)
        assertEquals("b", pending.first().localPlaylistId)
    }
}
