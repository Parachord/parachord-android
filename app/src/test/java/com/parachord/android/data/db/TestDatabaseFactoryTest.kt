package com.parachord.android.data.db

import org.junit.Assert.assertTrue
import org.junit.Test

class TestDatabaseFactoryTest {
    @Test
    fun `creates usable in-memory db`() {
        val db = TestDatabaseFactory.create()
        val count = db.playlistQueries.getAll().executeAsList().size
        assertTrue("expected fresh DB to have 0 playlists, got $count", count == 0)
    }
}
