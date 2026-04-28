package com.parachord.android.data.metadata

import android.content.Context
import android.util.Log
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbidMapperResult
import com.parachord.android.data.db.dao.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Background MBID enrichment service using the ListenBrainz MBID Mapper.
 *
 * Attaches MusicBrainz identifiers (recording, artist, release MBIDs) to tracks
 * in the collection. The MBID Mapper resolves in ~4ms with no strict rate limits,
 * making it suitable for batch enrichment.
 *
 * Results are cached to disk with a 90-day TTL (matching desktop's cache_mbid_mapper).
 * Misses are cached too (as null MBIDs) to avoid repeated lookups.
 *
 * Used by:
 * - FreshDropsRepository (artist MBID for release-group browsing)
 * - PlaybackController (enrich current track on playback)
 * - LibraryRepository (enrich on import)
 */
class MbidEnrichmentService constructor(
    private val context: Context,
    private val listenBrainzClient: ListenBrainzClient,
    private val trackDao: TrackDao,
) {
    companion object {
        private const val TAG = "MbidEnrichment"
        private const val CACHE_FILE = "mbid_mapper_cache.json"
        private const val CACHE_TTL = 90L * 24 * 60 * 60 * 1000 // 90 days
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-flight deduplication: don't enrich the same track concurrently. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Persistent disk cache: "artist|title" → MbidCacheEntry.
     * Loaded lazily on first access.
     */
    private var diskCache: MutableMap<String, MbidCacheEntry> = mutableMapOf()
    private var diskCacheLoaded = false

    // --- Public API ---

    /**
     * Enrich a track with MBIDs in the background.
     * Safe to call from anywhere — deduplicates, caches, and persists to Room.
     */
    fun enrichInBackground(trackId: String, artist: String, title: String) {
        if (artist.isBlank() || title.isBlank()) return
        if (!inFlight.add(trackId)) return // already in flight

        scope.launch {
            try {
                enrichTrack(trackId, artist, title)
            } finally {
                inFlight.remove(trackId)
            }
        }
    }

    /**
     * Enrich multiple tracks in the background (e.g. on bulk import).
     */
    fun enrichBatchInBackground(tracks: List<TrackEnrichmentRequest>) {
        scope.launch {
            for (req in tracks) {
                if (req.artist.isBlank() || req.title.isBlank()) continue
                if (!inFlight.add(req.trackId)) continue
                try {
                    enrichTrack(req.trackId, req.artist, req.title)
                } finally {
                    inFlight.remove(req.trackId)
                }
            }
            saveDiskCache()
        }
    }

    /**
     * Look up an artist MBID from the cache or via the mapper.
     * Returns null if not found. Does not persist to Room (no trackId context).
     * Used by FreshDropsRepository for release-group browsing.
     */
    suspend fun getArtistMbid(artistName: String, recordingName: String): String? {
        val key = cacheKey(artistName, recordingName)
        val cached = getCachedEntry(key)
        if (cached != null) return cached.artistMbid

        val result = mapperLookup(artistName, recordingName) ?: return null
        putCache(key, result)
        return result.artistMbid
    }

    /**
     * Check if we have a cached artist MBID for any track by this artist.
     * Faster than a full mapper call — scans the disk cache for a match.
     */
    fun getCachedArtistMbid(artistName: String): String? {
        ensureDiskCacheLoaded()
        val prefix = artistName.lowercase().trim() + "|"
        return diskCache.entries
            .firstOrNull { it.key.startsWith(prefix) && it.value.artistMbid != null }
            ?.value?.artistMbid
    }

    /**
     * Get canonical (MusicBrainz-corrected) names for a track from the disk cache.
     * Returns a pair of (canonicalArtist, canonicalRecording), or null if not cached.
     * Used by ScrobbleManager for canonical name fallback on scrobble payloads.
     */
    fun getCanonicalNames(artist: String, title: String): Pair<String, String>? {
        val key = cacheKey(artist, title)
        val entry = getCachedEntry(key) ?: return null
        val canonicalArtist = entry.canonicalArtistName ?: return null
        val canonicalRecording = entry.canonicalRecordingName ?: return null
        return Pair(canonicalArtist, canonicalRecording)
    }

    // --- Internal ---

    private suspend fun enrichTrack(trackId: String, artist: String, title: String) {
        val key = cacheKey(artist, title)

        // Check cache first
        val cached = getCachedEntry(key)
        if (cached != null) {
            // Persist to Room if we have MBIDs
            if (cached.hasAnyMbid) {
                trackDao.backfillMbids(trackId, cached.recordingMbid, cached.artistMbid, cached.releaseMbid)
            }
            return
        }

        // Call the mapper
        val result = mapperLookup(artist, title)
        val entry = if (result != null) {
            MbidCacheEntry(
                recordingMbid = result.recordingMbid,
                artistMbid = result.artistMbid,
                releaseMbid = result.releaseMbid,
                canonicalArtistName = result.artistCreditName,
                canonicalRecordingName = result.recordingName,
                cachedAt = System.currentTimeMillis(),
            )
        } else {
            // Cache the miss
            MbidCacheEntry(cachedAt = System.currentTimeMillis())
        }

        putCache(key, entry)

        // Persist to Room
        if (entry.hasAnyMbid) {
            trackDao.backfillMbids(trackId, entry.recordingMbid, entry.artistMbid, entry.releaseMbid)
            Log.d(TAG, "Enriched '$artist - $title' → artist=${entry.artistMbid}, rec=${entry.recordingMbid}")
        }
    }

    private suspend fun mapperLookup(artist: String, recording: String): MbidMapperResult? {
        return try {
            listenBrainzClient.mbidMapperLookup(artist, recording)
        } catch (e: Exception) {
            Log.w(TAG, "Mapper lookup failed for '$artist' - '$recording'", e)
            null
        }
    }

    private fun cacheKey(artist: String, title: String): String =
        "${artist.lowercase().trim()}|${title.lowercase().trim()}"

    private fun getCachedEntry(key: String): MbidCacheEntry? {
        ensureDiskCacheLoaded()
        val entry = diskCache[key] ?: return null
        // TTL check
        if (System.currentTimeMillis() - entry.cachedAt > CACHE_TTL) {
            diskCache.remove(key)
            return null
        }
        return entry
    }

    private fun putCache(key: String, result: MbidMapperResult) {
        putCache(key, MbidCacheEntry(
            recordingMbid = result.recordingMbid,
            artistMbid = result.artistMbid,
            releaseMbid = result.releaseMbid,
            canonicalArtistName = result.artistCreditName,
            canonicalRecordingName = result.recordingName,
            cachedAt = System.currentTimeMillis(),
        ))
    }

    private fun putCache(key: String, entry: MbidCacheEntry) {
        diskCache[key] = entry
    }

    private fun ensureDiskCacheLoaded() {
        if (diskCacheLoaded) return
        diskCacheLoaded = true
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return
            val entries = diskJson.decodeFromString<Map<String, MbidCacheEntry>>(file.readText())
            val now = System.currentTimeMillis()
            diskCache = entries.filterValues { now - it.cachedAt < CACHE_TTL }.toMutableMap()
            Log.d(TAG, "Loaded ${diskCache.size} cached MBIDs from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load MBID disk cache", e)
        }
    }

    internal fun saveDiskCache() {
        try {
            val file = File(context.filesDir, CACHE_FILE)
            val data: Map<String, MbidCacheEntry> = diskCache.toMap()
            file.writeText(diskJson.encodeToString(data))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save MBID disk cache", e)
        }
    }
}

/** Request to enrich a track with MBIDs. */
data class TrackEnrichmentRequest(
    val trackId: String,
    val artist: String,
    val title: String,
)

/** Persistent MBID cache entry with TTL. */
@Serializable
internal data class MbidCacheEntry(
    val recordingMbid: String? = null,
    val artistMbid: String? = null,
    val releaseMbid: String? = null,
    /** Canonical artist name from MusicBrainz (for scrobble name correction). */
    val canonicalArtistName: String? = null,
    /** Canonical recording name from MusicBrainz (for scrobble name correction). */
    val canonicalRecordingName: String? = null,
    val cachedAt: Long = 0L,
) {
    val hasAnyMbid: Boolean get() = !recordingMbid.isNullOrBlank() || !artistMbid.isNullOrBlank() || !releaseMbid.isNullOrBlank()
}
