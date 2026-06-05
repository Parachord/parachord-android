package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TrackTombstonesTest {

    /** In-memory fake mirroring desktop's makeStore({}). */
    private class FakeStore : TombstoneStore {
        var data: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        override fun read() = data
        override fun write(d: Map<String, Map<String, Tombstone>>) {
            data = d.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
        }
    }

    private val TTL = TrackTombstones.TTL_MS

    // ── addTombstone ──
    @Test fun `writes a new entry under provider+external`() {
        val s = FakeStore()
        assertTrue(TrackTombstones.addTombstone(s, "spotify", "abc123", 1000))
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(s, "spotify", "abc123"))
    }

    @Test fun `refreshes removedAt when called twice for same key`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc123", 1000)
        TrackTombstones.addTombstone(s, "spotify", "abc123", 2000)
        assertEquals(Tombstone(2000), TrackTombstones.getTombstone(s, "spotify", "abc123"))
    }

    @Test fun `keeps providers independent`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        TrackTombstones.addTombstone(s, "applemusic", "abc", 2000)
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(s, "spotify", "abc"))
        assertEquals(Tombstone(2000), TrackTombstones.getTombstone(s, "applemusic", "abc"))
    }

    @Test fun `rejects empty providerId`() {
        val s = FakeStore()
        assertFalse(TrackTombstones.addTombstone(s, "", "abc", 1000))
        assertTrue(s.data.isEmpty())
    }

    @Test fun `rejects empty externalId`() {
        val s = FakeStore()
        assertFalse(TrackTombstones.addTombstone(s, "spotify", "", 1000))
        assertTrue(s.data.isEmpty())
    }

    @Test fun `rejects null providerId or externalId`() {
        val s = FakeStore()
        assertFalse(TrackTombstones.addTombstone(s, null, "abc", 1000))
        assertFalse(TrackTombstones.addTombstone(s, "spotify", null, 1000))
    }

    // ── getTombstone ──
    @Test fun `returns null for missing keys`() {
        assertNull(TrackTombstones.getTombstone(FakeStore(), "spotify", "never"))
    }

    @Test fun `returns null for missing provider bucket`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        assertNull(TrackTombstones.getTombstone(s, "applemusic", "abc"))
    }

    // ── addTombstones (batch) ──
    @Test fun `batch writes multiple entries`() {
        val s = FakeStore()
        val n = TrackTombstones.addTombstones(s, listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("spotify", "b"),
            TombstoneEntry("applemusic", "c"),
        ), 1000)
        assertEquals(3, n)
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "a"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "b"))
        assertNotNull(TrackTombstones.getTombstone(s, "applemusic", "c"))
    }

    @Test fun `batch skips invalid without rejecting whole batch`() {
        val s = FakeStore()
        val n = TrackTombstones.addTombstones(s, listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("", "b"),
            TombstoneEntry("spotify", "c"),
        ), 1000)
        assertEquals(2, n)
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "a"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "c"))
    }

    @Test fun `batch returns 0 for empty input without touching store`() {
        val s = FakeStore()
        assertEquals(0, TrackTombstones.addTombstones(s, emptyList(), 1000))
        assertEquals(0, TrackTombstones.addTombstones(s, null, 1000))
        assertTrue(s.data.isEmpty())
    }

    // ── clearTombstone ──
    @Test fun `clears a single entry`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        assertTrue(TrackTombstones.clearTombstone(s, "spotify", "abc"))
        assertNull(TrackTombstones.getTombstone(s, "spotify", "abc"))
    }

    @Test fun `clear cleans up empty provider buckets`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "only", 1000)
        TrackTombstones.clearTombstone(s, "spotify", "only")
        assertFalse(s.data.containsKey("spotify"))
    }

    @Test fun `clear returns false when nothing to clear`() {
        assertFalse(TrackTombstones.clearTombstone(FakeStore(), "spotify", "nope"))
    }

    // ── clearTombstones (batch) ──
    @Test fun `batch clears across providers`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "a", 1000)
        TrackTombstones.addTombstone(s, "applemusic", "b", 1000)
        TrackTombstones.addTombstone(s, "spotify", "c", 1000)
        val cleared = TrackTombstones.clearTombstones(s, listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("applemusic", "b"),
        ))
        assertEquals(2, cleared)
        assertNull(TrackTombstones.getTombstone(s, "spotify", "a"))
        assertNull(TrackTombstones.getTombstone(s, "applemusic", "b"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "c"))
    }

    @Test fun `batch clear silently skips missing`() {
        assertEquals(0, TrackTombstones.clearTombstones(FakeStore(), listOf(
            TombstoneEntry("spotify", "never"),
        )))
    }

    // ── pruneExpired ──
    @Test fun `prunes entries older than TTL`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "old", 0)
        TrackTombstones.addTombstone(s, "spotify", "recent", TTL / 2)
        val pruned = TrackTombstones.pruneExpired(s, TTL, TTL + 1)
        assertEquals(1, pruned)
        assertNull(TrackTombstones.getTombstone(s, "spotify", "old"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "recent"))
    }

    @Test fun `prune cleans up empty provider buckets`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "only", 0)
        TrackTombstones.pruneExpired(s, TTL, TTL + 1)
        assertFalse(s.data.containsKey("spotify"))
    }

    @Test fun `prune returns 0 when nothing expired`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "recent", 1000)
        assertEquals(0, TrackTombstones.pruneExpired(s, TTL, 2000))
    }

    @Test fun `prune removes corrupt entries lacking removedAt`() {
        val s = FakeStore()
        s.data = mutableMapOf("spotify" to mutableMapOf("bad" to Tombstone(null)))
        val pruned = TrackTombstones.pruneExpired(s, TTL, 1000)
        assertEquals(1, pruned)
        assertNull(TrackTombstones.getTombstone(s, "spotify", "bad"))
    }

    // ── filterRemoteByTombstones ──
    private data class FakeItem(val externalId: String?, val title: String = "")

    @Test fun `filter drops items tombstoned for same provider`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        val items = listOf(FakeItem("abc", "tombstoned"), FakeItem("def", "kept"))
        val r = TrackTombstones.filterRemoteByTombstones(s, items, "spotify", 9999) { it.externalId }
        assertEquals(listOf("kept"), r.filtered!!.map { it.title })
        assertEquals(1, r.dropped)
    }

    @Test fun `filter re-arms TTL on hit`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("abc")), "spotify", 5000) { it.externalId }
        assertEquals(Tombstone(5000), TrackTombstones.getTombstone(s, "spotify", "abc"))
    }

    @Test fun `filter does not touch other providers`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "applemusic", "abc", 1000)
        val r = TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("abc")), "spotify", 5000) { it.externalId }
        assertEquals(1, r.filtered!!.size)
        assertEquals(0, r.dropped)
        assertEquals(1000L, TrackTombstones.getTombstone(s, "applemusic", "abc")!!.removedAt)
    }

    @Test fun `filter handles empty or null items`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        val empty = TrackTombstones.filterRemoteByTombstones(s, emptyList<FakeItem>(), "spotify", 1) { it.externalId }
        assertEquals(0, empty.dropped); assertTrue(empty.filtered!!.isEmpty())
        val nul = TrackTombstones.filterRemoteByTombstones<FakeItem>(s, null, "spotify", 1) { it.externalId }
        assertEquals(0, nul.dropped); assertNull(nul.filtered)
    }

    @Test fun `filter passes through items without externalId`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        val items = listOf(FakeItem(null, "no-ext"), FakeItem(null), FakeItem("abc"))
        val r = TrackTombstones.filterRemoteByTombstones(s, items, "spotify", 9999) { it.externalId }
        assertEquals(2, r.filtered!!.size)
        assertEquals(1, r.dropped)
    }

    // ── integration ──
    @Test fun `end-to-end add filter clear filter`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "track1", 1000)
        val sync1 = TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("track1", "foo")), "spotify", 2000) { it.externalId }
        assertEquals(1, sync1.dropped)
        assertEquals(2000L, TrackTombstones.getTombstone(s, "spotify", "track1")!!.removedAt)
        TrackTombstones.clearTombstones(s, listOf(TombstoneEntry("spotify", "track1")))
        val sync2 = TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("track1", "foo")), "spotify", 3000) { it.externalId }
        assertEquals(0, sync2.dropped)
        assertEquals(1, sync2.filtered!!.size)
    }
}
