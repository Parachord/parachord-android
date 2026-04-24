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
}
