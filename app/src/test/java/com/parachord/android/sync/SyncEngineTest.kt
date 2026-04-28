package com.parachord.android.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SyncEngine's mass removal safeguard and diff logic.
 * The mass removal safeguard prevents accidental data loss when the
 * remote provider returns a significantly reduced list (e.g. API error
 * returning partial data).
 */
class SyncEngineTest {

    companion object {
        private const val MASS_REMOVAL_THRESHOLD_PERCENT = 0.25
        private const val MASS_REMOVAL_THRESHOLD_COUNT = 50
    }

    /**
     * Replicates SyncEngine's mass removal check logic.
     * Returns true if removals should be skipped (safeguard triggered).
     */
    private fun shouldSkipRemovals(toRemoveCount: Int, localCount: Int): Boolean {
        return toRemoveCount > localCount * MASS_REMOVAL_THRESHOLD_PERCENT &&
            toRemoveCount > MASS_REMOVAL_THRESHOLD_COUNT
    }

    // -- Mass removal safeguard --

    @Test
    fun `safeguard triggers when removing more than 25 percent AND more than 50 items`() {
        // 200 local items, removing 60 (30%) — triggers both conditions
        assertTrue(shouldSkipRemovals(60, 200))
    }

    @Test
    fun `safeguard does NOT trigger when removing less than 25 percent`() {
        // 200 local items, removing 40 (20%) — below percent threshold
        assertFalse(shouldSkipRemovals(40, 200))
    }

    @Test
    fun `safeguard does NOT trigger when removing less than 50 items even if over 25 percent`() {
        // 100 local items, removing 30 (30%) — over percent but under absolute count
        assertFalse(shouldSkipRemovals(30, 100))
    }

    @Test
    fun `safeguard does NOT trigger for small collections`() {
        // 10 local items, removing 5 (50%) — over percent but under 50 absolute
        assertFalse(shouldSkipRemovals(5, 10))
    }

    @Test
    fun `safeguard triggers at exact boundary - 51 of 200`() {
        // 51 > 200*0.25 (50) AND 51 > 50
        assertTrue(shouldSkipRemovals(51, 200))
    }

    @Test
    fun `safeguard does NOT trigger at exact boundary - 50 of 200`() {
        // 50 is NOT > 200*0.25 (50.0) — equals, not greater than
        assertFalse(shouldSkipRemovals(50, 200))
    }

    @Test
    fun `safeguard allows full removal of small collection`() {
        // 20 local items, removing all 20 — over 25% but under 50 count
        assertFalse(shouldSkipRemovals(20, 20))
    }

    @Test
    fun `safeguard triggers for large-scale removal`() {
        // 1000 local items, removing 500 (50%)
        assertTrue(shouldSkipRemovals(500, 1000))
    }

    @Test
    fun `safeguard allows zero removals`() {
        assertFalse(shouldSkipRemovals(0, 1000))
    }

    @Test
    fun `safeguard handles empty local collection`() {
        assertFalse(shouldSkipRemovals(0, 0))
    }

    // -- Diff logic --

    @Test
    fun `diff identifies items to add`() {
        val remote = setOf("a", "b", "c", "d")
        val local = setOf("a", "b")
        val toAdd = remote - local
        assertEquals(setOf("c", "d"), toAdd)
    }

    @Test
    fun `diff identifies items to remove`() {
        val remote = setOf("a", "b")
        val local = setOf("a", "b", "c", "d")
        val toRemove = local - remote
        assertEquals(setOf("c", "d"), toRemove)
    }

    @Test
    fun `diff identifies unchanged items`() {
        val remote = setOf("a", "b", "c")
        val local = setOf("a", "b", "d")
        val unchanged = remote.intersect(local)
        assertEquals(setOf("a", "b"), unchanged)
    }

    @Test
    fun `diff handles identical sets`() {
        val remote = setOf("a", "b", "c")
        val local = setOf("a", "b", "c")
        val toAdd = remote - local
        val toRemove = local - remote
        assertTrue(toAdd.isEmpty())
        assertTrue(toRemove.isEmpty())
    }

    @Test
    fun `diff handles empty remote (full removal)`() {
        val remote = emptySet<String>()
        val local = setOf("a", "b", "c")
        val toRemove = local - remote
        assertEquals(local, toRemove)
    }

    // -- TypeSyncResult aggregation --

    @Test
    fun `TypeSyncResult default values are all zero`() {
        val result = com.parachord.shared.sync.SyncEngine.TypeSyncResult()
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertEquals(0, result.updated)
        assertEquals(0, result.unchanged)
    }

    @Test
    fun `FullSyncResult defaults to success`() {
        val result = com.parachord.shared.sync.SyncEngine.FullSyncResult()
        assertTrue(result.success)
        assertNull(result.error)
    }

    // -- SyncPhase --

    @Test
    fun `SyncPhase has correct order`() {
        val phases = com.parachord.shared.sync.SyncEngine.SyncPhase.entries
        assertEquals(5, phases.size)
        assertEquals(com.parachord.shared.sync.SyncEngine.SyncPhase.TRACKS, phases[0])
        assertEquals(com.parachord.shared.sync.SyncEngine.SyncPhase.ALBUMS, phases[1])
        assertEquals(com.parachord.shared.sync.SyncEngine.SyncPhase.ARTISTS, phases[2])
        assertEquals(com.parachord.shared.sync.SyncEngine.SyncPhase.PLAYLISTS, phases[3])
        assertEquals(com.parachord.shared.sync.SyncEngine.SyncPhase.COMPLETE, phases[4])
    }
}
