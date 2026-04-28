package com.parachord.android.data.repository

import android.content.Context
import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbReleaseGroupEntry
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.metadata.MusicBrainzProvider
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Repository for Fresh Drops — new releases from artists the user listens to.
 *
 * Mirrors the desktop app's `loadNewReleases()` approach:
 * 1. Gather artists from library + Last.fm/ListenBrainz top artists
 * 2. For each artist, resolve MBID (mapper first, then MusicBrainz search fallback)
 * 3. Browse their release-groups and filter to recent releases (last 6 months)
 * 4. Assign Cover Art Archive URLs
 * 5. Cache with 6-hour TTL
 *
 * Uses the ListenBrainz MBID Mapper (mapper.listenbrainz.org) for fast (~4ms)
 * artist MBID resolution, avoiding rate-limited MusicBrainz search calls.
 * MBID results are cached to disk with a 90-day TTL (matching desktop).
 */
class FreshDropsRepository constructor(
    private val context: Context,
    private val musicBrainzClient: MusicBrainzClient,
    private val lastFmClient: LastFmClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val trackDao: TrackDao,
    private val mbidEnrichment: MbidEnrichmentService,
) {
    companion object {
        private const val TAG = "FreshDropsRepo"
        private const val MAX_ARTISTS_TO_CHECK = 50
        private const val STALE_THRESHOLD = 6 * 60 * 60 * 1000L // 6 hours
        private const val CACHE_FILE = "fresh_drops_cache.json"
        private const val ROTATION_FILE = "fresh_drops_rotation.json"
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache, initialized from disk on first access. */
    private var cachedReleases: List<FreshDrop>? = null
    private var lastFetchedAt: Long = 0L
    private var diskCacheLoaded = false

    /** Round-robin rotation: tracks when each artist was last checked. */
    private var artistLastChecked: MutableMap<String, Long> = mutableMapOf()
    private var rotationLoaded = false

    /** Synchronous access to cached releases (for ViewModel initial state). */
    val cached: List<FreshDrop>?
        get() {
            if (!diskCacheLoaded) loadDiskCache()
            return cachedReleases
        }
    val isCacheStale: Boolean get() = System.currentTimeMillis() - lastFetchedAt > STALE_THRESHOLD

    /** In-memory MBID cache for this session: artist name (lowercase) → MBID. */
    private val mbidCache = mutableMapOf<String, String>()

    /** Load cache from disk on first access. */
    private fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return
            val wrapper = diskJson.decodeFromString<FreshDropsDiskCache>(file.readText())
            cachedReleases = wrapper.releases
            lastFetchedAt = wrapper.fetchedAt
            Log.d(TAG, "Loaded ${wrapper.releases.size} cached fresh drops from disk (age: ${(System.currentTimeMillis() - wrapper.fetchedAt) / 1000}s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    /** Persist cache to disk. */
    private fun saveDiskCache(releases: List<FreshDrop>, fetchedAt: Long) {
        try {
            val wrapper = FreshDropsDiskCache(releases = releases, fetchedAt = fetchedAt)
            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(diskJson.encodeToString(wrapper))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /** Load round-robin rotation state from disk. */
    private fun loadRotation() {
        rotationLoaded = true
        try {
            val file = File(context.filesDir, ROTATION_FILE)
            if (!file.exists()) return
            val map = diskJson.decodeFromString<Map<String, Long>>(file.readText())
            artistLastChecked = map.toMutableMap()
            Log.d(TAG, "Loaded rotation state for ${artistLastChecked.size} artists")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load rotation state", e)
        }
    }

    /** Persist round-robin rotation state to disk. */
    private fun saveRotation() {
        try {
            val file = File(context.filesDir, ROTATION_FILE)
            file.writeText(diskJson.encodeToString(artistLastChecked.toMap()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save rotation state", e)
        }
    }

    /** Record that an artist was checked at the given time. */
    private fun markArtistChecked(artistName: String) {
        artistLastChecked[artistName.lowercase().trim()] = System.currentTimeMillis()
    }

    /**
     * Resolve artist MBID using the shared MbidEnrichmentService, falling back
     * to MusicBrainz search.
     *
     * The enrichment service uses the MBID Mapper (~4ms, no rate limits) with a
     * 90-day disk cache. We only fall back to rate-limited MusicBrainz search for
     * artists without library tracks (e.g. Last.fm/ListenBrainz-only artists).
     */
    private suspend fun resolveArtistMbid(
        artistName: String,
        libraryTracks: List<com.parachord.android.data.db.entity.TrackEntity>,
    ): MbidResolution {
        val key = artistName.lowercase().trim()

        // 1. Check session cache
        mbidCache[key]?.let { return MbidResolution(it, usedMbSearch = false) }

        // 2. Check shared enrichment cache (disk-backed, 90-day TTL)
        mbidEnrichment.getCachedArtistMbid(artistName)?.let { mbid ->
            mbidCache[key] = mbid
            return MbidResolution(mbid, usedMbSearch = false)
        }

        // 3. Try MBID Mapper via enrichment service using a library track
        val trackByArtist = libraryTracks.firstOrNull { it.artist.lowercase().trim() == key }
        if (trackByArtist != null) {
            val mbid = mbidEnrichment.getArtistMbid(trackByArtist.artist, trackByArtist.title)
            if (mbid != null) {
                mbidCache[key] = mbid
                Log.d(TAG, "MBID Mapper enriched '$artistName' → $mbid")
                return MbidResolution(mbid, usedMbSearch = false)
            }
        }

        // 4. Fall back to MusicBrainz artist search (rate-limited)
        try {
            val results = musicBrainzClient.searchArtists(artistName, limit = 1)
            val artist = results.artists.firstOrNull()
            if (artist != null) {
                mbidCache[key] = artist.id
                Log.d(TAG, "MB search resolved '$artistName' → ${artist.id}")
                return MbidResolution(artist.id, usedMbSearch = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MB search failed for '$artistName'", e)
        }

        return MbidResolution(null, usedMbSearch = true)
    }

    /**
     * Get fresh drops with progressive loading.
     *
     * Matches the desktop's loadNewReleases() approach:
     * - Show cached data immediately while refreshing
     * - Emit progressive results as each artist is processed
     * - Merge new results with prior cache (each fetch only covers a subset
     *   of the user's library due to the artist cap + shuffling)
     * - Cache results in memory for the session
     */
    fun getFreshDrops(forceRefresh: Boolean = false): Flow<Resource<List<FreshDrop>>> = flow {
        // Ensure disk cache is loaded before checking staleness
        if (!diskCacheLoaded) loadDiskCache()

        val now = System.currentTimeMillis()
        val isStale = now - lastFetchedAt > STALE_THRESHOLD

        // Return cache if fresh
        if (!forceRefresh && !isStale && cachedReleases != null) {
            Log.d(TAG, "Using ${cachedReleases!!.size} cached fresh drops")
            emit(Resource.Success(cachedReleases!!))
            return@flow
        }

        // Show stale cache immediately while refreshing (desktop pattern)
        if (cachedReleases != null) {
            Log.d(TAG, "Showing ${cachedReleases!!.size} stale cached releases while refreshing...")
            emit(Resource.Success(cachedReleases!!))
        } else {
            emit(Resource.Loading)
        }

        try {
            // 1. Gather artists from multiple sources
            val libraryTracks = withContext(Dispatchers.IO) {
                try { trackDao.getRecentSync(200) } catch (_: Exception) { emptyList() }
            }
            val artists = withContext(Dispatchers.IO) { gatherArtists(libraryTracks) }
            if (artists.isEmpty()) {
                Log.d(TAG, "No artists found in collection or history")
                if (cachedReleases == null) {
                    emit(Resource.Error("Add artists to your library or connect Last.fm/ListenBrainz to discover new releases"))
                }
                return@flow
            }

            Log.d(TAG, "Checking ${artists.size} artists for new releases...")

            // Snapshot prior cached releases before fetch starts (desktop pattern:
            // merge new + prior so we don't lose results from artists not in this
            // run's selection — the artist cap means each fetch only covers a subset)
            val priorCachedReleases = cachedReleases?.toList() ?: emptyList()

            // 2. Fetch releases for each artist from MusicBrainz
            val sixMonthsAgo = LocalDate.now().minusMonths(6)
            val allReleases = mutableListOf<FreshDrop>()

            for ((index, artist) in artists.withIndex()) {
                try {
                    val releases = withContext(Dispatchers.IO) {
                        fetchReleasesForArtist(artist.name, sixMonthsAgo, libraryTracks)
                    }
                    val withSource = releases.map { it.copy(artistSource = artist.source) }
                    allReleases.addAll(withSource)

                    // Record this artist as checked for round-robin rotation
                    markArtistChecked(artist.name)

                    // Emit progressive results every 5 artists (desktop pattern:
                    // show releases as they come in, merged with prior cache)
                    if (allReleases.isNotEmpty() && (index + 1) % 5 == 0) {
                        val progressMerged = mergeAndDedupe(allReleases, priorCachedReleases)
                        // Save partial results so cache survives if collector is cancelled
                        cachedReleases = progressMerged
                        lastFetchedAt = System.currentTimeMillis()
                        saveDiskCache(progressMerged, lastFetchedAt)
                        emit(Resource.Success(progressMerged))
                    }

                    // Only rate-limit if we used MusicBrainz search (mapper is fast/unlimited)
                    // The browse call always hits MB, so always delay for that
                    delay(1100)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Re-throw cancellation so the loop terminates cleanly instead
                    // of catching it in the generic Exception handler below and
                    // continuing into 20 phantom "failed" iterations.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch releases for '${artist.name}'", e)
                }
            }

            // Persist rotation state so next refresh picks up where we left off
            withContext(Dispatchers.IO) { saveRotation() }

            // 3. Final merge: new results + prior cache, deduplicated and sorted
            val finalReleases = mergeAndDedupe(allReleases, priorCachedReleases)

            Log.d(TAG, "Found ${allReleases.size} fresh + ${priorCachedReleases.size} cached → ${finalReleases.size} unique releases from ${artists.size} artists")

            cachedReleases = finalReleases
            lastFetchedAt = System.currentTimeMillis()
            saveDiskCache(finalReleases, lastFetchedAt)
            emit(Resource.Success(finalReleases))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load fresh drops", e)
            if (cachedReleases == null) {
                emit(Resource.Error("Failed to load new releases"))
            }
        }
    }

    /**
     * Merge fresh releases with prior cached releases, deduplicate, assign
     * Cover Art Archive URLs, and sort by date descending.
     * Fresh results take priority (listed first before dedup).
     */
    private fun mergeAndDedupe(
        freshReleases: List<FreshDrop>,
        priorReleases: List<FreshDrop>,
    ): List<FreshDrop> {
        val merged = freshReleases + priorReleases
        val seen = mutableSetOf<String>()
        return merged
            .filter { release ->
                val key = "${release.artist.lowercase()}|${release.title.lowercase()}"
                seen.add(key)
            }
            .map { release ->
                if (release.albumArt == null && release.mbid != null) {
                    release.copy(albumArt = MusicBrainzProvider.releaseGroupArtUrl(release.mbid))
                } else release
            }
            .sortedByDescending { it.date ?: "" }
    }

    /**
     * Gather unique artists from multiple sources (matching desktop's gatherNewReleasesArtists):
     * 1. Library tracks (distinct artists)
     * 2. Last.fm top artists
     * 3. ListenBrainz top artists
     *
     * Deduplicates by lowercased name, then sorts by round-robin rotation
     * (least-recently-checked first) so every artist eventually gets checked
     * across successive refreshes. Caps at MAX_ARTISTS_TO_CHECK.
     */
    private suspend fun gatherArtists(
        libraryTracks: List<com.parachord.android.data.db.entity.TrackEntity>,
    ): List<ArtistSource> {
        if (!rotationLoaded) loadRotation()

        val seen = mutableSetOf<String>()
        val artists = mutableListOf<ArtistSource>()

        // Source 1: Library artists
        try {
            val libraryArtists = libraryTracks
                .map { it.artist }
                .distinct()
                .filter { seen.add(it.lowercase().trim()) }
                .map { ArtistSource(name = it, source = "library") }
            artists.addAll(libraryArtists)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get library artists", e)
        }

        // Source 2: Last.fm top artists
        try {
            val lfmUsername = settingsStore.getLastFmUsername()
            if (lfmUsername != null) {
                val apiKey = BuildConfig.LASTFM_API_KEY
                val topArtists = lastFmClient.getUserTopArtists(
                    user = lfmUsername, apiKey = apiKey, limit = 50, period = "6month",
                )
                val lfmArtists = topArtists.topartists?.artist
                    ?.map { it.name }
                    ?.filter { seen.add(it.lowercase().trim()) }
                    ?.map { ArtistSource(name = it, source = "history") }
                    ?: emptyList()
                artists.addAll(lfmArtists)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Last.fm top artists", e)
        }

        // Source 3: ListenBrainz top artists
        try {
            val lbUsername = settingsStore.getListenBrainzUsername()
            if (lbUsername != null) {
                val lbArtists = listenBrainzClient.getUserTopArtists(lbUsername, "half_yearly", 50)
                    .map { it.name }
                    .filter { seen.add(it.lowercase().trim()) }
                    .map { ArtistSource(name = it, source = "history") }
                artists.addAll(lbArtists)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ListenBrainz top artists", e)
        }

        // Round-robin: sort by last-checked time (never-checked = 0, i.e. highest priority).
        // Within same last-checked time (e.g. all never-checked), shuffle to avoid
        // always processing the same source order.
        val sorted = artists
            .groupBy { artistLastChecked[it.name.lowercase().trim()] ?: 0L }
            .toSortedMap()
            .flatMap { (_, group) -> group.shuffled() }

        Log.d(TAG, "Gathered ${sorted.size} unique artists, checking ${minOf(sorted.size, MAX_ARTISTS_TO_CHECK)}")
        return sorted.take(MAX_ARTISTS_TO_CHECK)
    }

    /**
     * Fetch recent releases for an artist from MusicBrainz.
     * Matches desktop's fetchReleasesForArtists() logic:
     * 1. Search for artist MBID
     * 2. Browse release-groups
     * 3. Filter to releases after cutoff date
     * 4. Exclude compilations, broadcasts, live
     */
    private suspend fun fetchReleasesForArtist(
        artistName: String,
        cutoffDate: LocalDate,
        libraryTracks: List<com.parachord.android.data.db.entity.TrackEntity>,
    ): List<FreshDrop> {
        // Resolve MBID: mapper first (~4ms), MB search fallback (rate-limited)
        val resolution = resolveArtistMbid(artistName, libraryTracks)
        val mbid = resolution.mbid ?: return emptyList()

        // Browse release-groups with pagination — MusicBrainz browse results are
        // NOT ordered by date, so with limit=100 we can miss the newest releases
        // for prolific artists whose discography exceeds one page.
        val allReleaseGroups = mutableListOf<MbReleaseGroupEntry>()
        var offset = 0
        var totalCount = Int.MAX_VALUE
        var lastPageSize: Int
        val pageSize = 100
        do {
            val response = musicBrainzClient.browseReleaseGroups(mbid, limit = pageSize, offset = offset)
            allReleaseGroups.addAll(response.releaseGroups)
            totalCount = response.releaseGroupCount
            lastPageSize = response.releaseGroups.size
            offset += lastPageSize
            // Rate-limit between pages (MusicBrainz 1 req/sec policy)
            if (offset < totalCount && lastPageSize == pageSize) {
                delay(1100)
            }
        } while (offset < totalCount && offset < 500 && lastPageSize == pageSize)

        val cutoffStr = cutoffDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        return allReleaseGroups
            .filter { rg ->
                // Must have a release date
                val date = rg.firstReleaseDate ?: return@filter false
                // MusicBrainz returns dates as "YYYY-MM-DD", "YYYY-MM", or "YYYY".
                // Pad partial dates to full precision for comparison — use the
                // earliest possible day so we INCLUDE releases whose partial date
                // *could* be after the cutoff (e.g. "2025" → "2025-01-01" keeps
                // any 2025 release if the cutoff year is 2025).
                val comparable = when (date.length) {
                    4 -> "$date-01-01"     // "2025" → "2025-01-01"
                    7 -> "$date-01"        // "2025-09" → "2025-09-01"
                    else -> date           // "2025-09-22" already full
                }
                // Must be after cutoff (last 6 months)
                comparable >= cutoffStr
                    // Exclude broadcast as primary type
                    && rg.primaryType?.lowercase() != "broadcast"
                    // Exclude compilations, broadcasts, live, DJ-mix, remix, etc.
                    && rg.secondaryTypes.none { type ->
                    type.lowercase() in listOf(
                        "compilation", "broadcast", "live",
                        "dj-mix", "remix", "mixtape/street",
                    )
                }
            }
            .map { rg ->
                FreshDrop(
                    mbid = rg.id,
                    title = rg.title,
                    artist = rg.artistName.ifBlank { artistName },
                    date = rg.firstReleaseDate,
                    releaseType = rg.primaryType?.lowercase() ?: "album",
                    secondaryTypes = rg.secondaryTypes,
                )
            }
    }
}

/** An artist from one of the discovery sources. */
private data class ArtistSource(
    val name: String,
    val source: String, // "library", "history"
)

/** Result of resolving an artist MBID (mapper or MB search). */
private data class MbidResolution(
    val mbid: String?,
    val usedMbSearch: Boolean,
)

/** Disk cache wrapper for JSON serialization. */
@Serializable
private data class FreshDropsDiskCache(
    val releases: List<FreshDrop>,
    val fetchedAt: Long,
)

/**
 * A new release (fresh drop).
 */
@Serializable
data class FreshDrop(
    val mbid: String? = null,
    val title: String,
    val artist: String,
    val artistSource: String = "",
    val date: String? = null,
    val releaseType: String = "album",
    val secondaryTypes: List<String> = emptyList(),
    val albumArt: String? = null,
) {
    /** True if this release date is in the future. */
    val isUpcoming: Boolean
        get() {
            val d = date ?: return false
            return try {
                LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE).isAfter(LocalDate.now())
            } catch (_: Exception) { false }
        }

    /** Formatted display date. */
    val displayDate: String
        get() {
            val d = date ?: return ""
            return try {
                val parsed = LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE)
                if (isUpcoming) {
                    "Coming ${parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
                } else {
                    parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                }
            } catch (_: Exception) { d }
        }
}
