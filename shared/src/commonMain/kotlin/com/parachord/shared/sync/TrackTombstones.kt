package com.parachord.shared.sync

import kotlinx.serialization.Serializable

/**
 * A single tombstone payload. `removedAt` is nullable so a deserialized
 * corrupt entry (`{}`) round-trips to `removedAt = null` and is pruned —
 * mirrors desktop's "removes corrupt entries lacking removedAt" semantics.
 */
@Serializable
data class Tombstone(val removedAt: Long? = null)

/** A (providerId, externalId) pair for batch add/clear. */
data class TombstoneEntry(val providerId: String, val externalId: String)

/** Result of [TrackTombstones.filterRemoteByTombstones]. */
data class TombstoneFilterResult<T>(val filtered: List<T>?, val dropped: Int)

/**
 * Store abstraction over the persisted tombstone blob. `read()` returns a
 * MUTABLE map the caller may mutate before `write()`. Production impl is
 * KvTombstoneStore (JSON in KvStore); tests inject an in-memory fake.
 *
 * Map shape: providerId -> (externalId -> Tombstone).
 */
interface TombstoneStore {
    fun read(): MutableMap<String, MutableMap<String, Tombstone>>
    fun write(data: Map<String, Map<String, Tombstone>>)
}

/**
 * Durable "user removed this on purpose" markers, keyed by (providerId,
 * externalId), TTL'd with re-arm on every sync that confirms the remote
 * still has the track. Pure functions over a [TombstoneStore]. Verbatim
 * port of desktop sync-engine/tombstones.js (parachord#864 / #172).
 */
object TrackTombstones {
    const val TTL_MS: Long = 365L * 24L * 60L * 60L * 1000L // 365 days (all Long to avoid Int overflow)

    private fun valid(s: String?): Boolean = !s.isNullOrEmpty()

    fun addTombstone(store: TombstoneStore, providerId: String?, externalId: String?, now: Long): Boolean {
        if (!valid(providerId) || !valid(externalId)) return false
        val all = store.read()
        all.getOrPut(providerId!!) { mutableMapOf() }[externalId!!] = Tombstone(now)
        store.write(all)
        return true
    }

    fun addTombstones(store: TombstoneStore, entries: List<TombstoneEntry>?, now: Long): Int {
        if (entries.isNullOrEmpty()) return 0
        val all = store.read()
        var written = 0
        for (e in entries) {
            if (!valid(e.providerId) || !valid(e.externalId)) continue
            all.getOrPut(e.providerId) { mutableMapOf() }[e.externalId] = Tombstone(now)
            written++
        }
        if (written > 0) store.write(all)
        return written
    }

    fun getTombstone(store: TombstoneStore, providerId: String, externalId: String): Tombstone? =
        store.read()[providerId]?.get(externalId)

    fun clearTombstone(store: TombstoneStore, providerId: String, externalId: String): Boolean {
        val all = store.read()
        val bucket = all[providerId] ?: return false
        if (bucket.remove(externalId) == null) return false
        if (bucket.isEmpty()) all.remove(providerId)
        store.write(all)
        return true
    }

    fun clearTombstones(store: TombstoneStore, entries: List<TombstoneEntry>?): Int {
        if (entries.isNullOrEmpty()) return 0
        val all = store.read()
        var cleared = 0
        for (e in entries) {
            val bucket = all[e.providerId] ?: continue
            if (bucket.remove(e.externalId) != null) {
                cleared++
                if (bucket.isEmpty()) all.remove(e.providerId)
            }
        }
        if (cleared > 0) store.write(all)
        return cleared
    }

    fun pruneExpired(store: TombstoneStore, ttlMs: Long, now: Long): Int {
        val all = store.read()
        var pruned = 0
        val providerIds = all.keys.toList()
        for (providerId in providerIds) {
            val bucket = all[providerId] ?: continue
            val externalIds = bucket.keys.toList()
            for (externalId in externalIds) {
                val removedAt = bucket[externalId]?.removedAt
                if (removedAt == null || (now - removedAt) > ttlMs) {
                    bucket.remove(externalId)
                    pruned++
                }
            }
            if (bucket.isEmpty()) all.remove(providerId)
        }
        if (pruned > 0) store.write(all)
        return pruned
    }

    fun <T> filterRemoteByTombstones(
        store: TombstoneStore,
        items: List<T>?,
        providerId: String?,
        now: Long,
        externalIdOf: (T) -> String?,
    ): TombstoneFilterResult<T> {
        if (items.isNullOrEmpty()) return TombstoneFilterResult(items, 0)
        if (!valid(providerId)) return TombstoneFilterResult(items, 0)
        val all = store.read()
        val providerMap = all[providerId] ?: return TombstoneFilterResult(items, 0)
        val kept = mutableListOf<T>()
        var dropped = 0
        var touched = false
        for (item in items) {
            val ext = externalIdOf(item)
            if (ext != null && providerMap.containsKey(ext)) {
                providerMap[ext] = Tombstone(now) // re-arm TTL
                touched = true
                dropped++
            } else {
                kept.add(item)
            }
        }
        if (touched) store.write(all)
        return TombstoneFilterResult(kept, dropped)
    }
}
