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

    // ── Phase 3: multi-provider propagation predicates ───────────────

    @Test
    fun `countOtherMirrors returns 1 when a different provider has a link`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "applemusic", "y", null, 2L)
        val others = db.syncPlaylistLinkQueries.countOtherMirrors("p1", "spotify").executeAsOne()
        assertEquals(1L, others)
    }

    @Test
    fun `countOtherMirrors returns 0 when only the current provider is linked`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
        val others = db.syncPlaylistLinkQueries.countOtherMirrors("p1", "spotify").executeAsOne()
        assertEquals(0L, others)
    }

    @Test
    fun `selectMirrorsExcluding returns mirrors NOT matching the excluded provider`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "applemusic", "y", null, 2L)
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "tidal", "z", null, 3L)
        val rows = db.syncPlaylistLinkQueries.selectMirrorsExcluding("p1", "spotify").executeAsList()
        assertEquals(2, rows.size)
        assertEquals(true, rows.all { it.providerId != "spotify" })
    }

    @Test
    fun `selectMirrorsExcluding returns all mirrors when excluded provider not present`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
        db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "applemusic", "y", null, 2L)
        val rows = db.syncPlaylistLinkQueries.selectMirrorsExcluding("p1", "no-such-provider").executeAsList()
        assertEquals(2, rows.size)
    }
}
