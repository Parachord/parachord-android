package com.parachord.android.data.repository

import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.api.ListenBrainzApi
import com.parachord.android.data.api.MusicBrainzApi
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.metadata.MusicBrainzProvider
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Fresh Drops — new releases from artists the user listens to.
 *
 * Mirrors the desktop app's `loadNewReleases()` approach:
 * 1. Gather artists from library + Last.fm/ListenBrainz top artists
 * 2. For each artist, search MusicBrainz for their MBID
 * 3. Browse their release-groups and filter to recent releases (last 6 months)
 * 4. Assign Cover Art Archive URLs
 * 5. Cache with 6-hour TTL
 */
@Singleton
class FreshDropsRepository @Inject constructor(
    private val musicBrainzApi: MusicBrainzApi,
    private val lastFmApi: LastFmApi,
    private val listenBrainzApi: ListenBrainzApi,
    private val settingsStore: SettingsStore,
    private val trackDao: TrackDao,
) {
    companion object {
        private const val TAG = "FreshDropsRepo"
        private const val MAX_ARTISTS_TO_CHECK = 50
        private const val STALE_THRESHOLD = 6 * 60 * 60 * 1000L // 6 hours
    }

    /** In-memory cache. */
    private var cachedReleases: List<FreshDrop>? = null
    private var lastFetchedAt: Long = 0L

    /** MBID cache to avoid repeated artist lookups. */
    private val mbidCache = mutableMapOf<String, String>()

    /**
     * Get fresh drops with progressive loading.
     * First emits cached data if available, then fetches fresh data.
     */
    fun getFreshDrops(forceRefresh: Boolean = false): Flow<Resource<List<FreshDrop>>> = flow {
        emit(Resource.Loading)

        try {
            val now = System.currentTimeMillis()
            val isStale = now - lastFetchedAt > STALE_THRESHOLD

            // Return cache if fresh
            if (!forceRefresh && !isStale && cachedReleases != null) {
                emit(Resource.Success(cachedReleases!!))
                return@flow
            }

            // Show stale cache while refreshing
            if (cachedReleases != null && isStale && !forceRefresh) {
                emit(Resource.Success(cachedReleases!!))
            }

            // 1. Gather artists from multiple sources
            val artists = withContext(Dispatchers.IO) { gatherArtists() }
            if (artists.isEmpty()) {
                if (cachedReleases == null) {
                    emit(Resource.Error("Add artists to your library or connect Last.fm/ListenBrainz to discover new releases"))
                }
                return@flow
            }

            Log.d(TAG, "Checking ${artists.size} artists for new releases...")

            // 2. Fetch releases for each artist from MusicBrainz
            val sixMonthsAgo = LocalDate.now().minusMonths(6)
            val allReleases = mutableListOf<FreshDrop>()

            for (artist in artists) {
                try {
                    val releases = withContext(Dispatchers.IO) {
                        fetchReleasesForArtist(artist.name, sixMonthsAgo)
                    }
                    allReleases.addAll(releases.map { it.copy(artistSource = artist.source) })

                    // Rate limit MusicBrainz (1 req/sec policy — 2 calls per artist)
                    delay(1100)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch releases for '${artist.name}'", e)
                }
            }

            // 3. Deduplicate and sort by date DESC
            val seen = mutableSetOf<String>()
            val deduped = allReleases.filter { release ->
                val key = "${release.artist.lowercase()}|${release.title.lowercase()}"
                seen.add(key)
            }.sortedByDescending { it.date ?: "" }

            Log.d(TAG, "Found ${deduped.size} fresh drops from ${artists.size} artists")

            // 4. Assign Cover Art Archive URLs
            val withArt = deduped.map { release ->
                if (release.mbid != null) {
                    release.copy(albumArt = MusicBrainzProvider.releaseGroupArtUrl(release.mbid))
                } else release
            }

            cachedReleases = withArt
            lastFetchedAt = System.currentTimeMillis()
            emit(Resource.Success(withArt))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load fresh drops", e)
            if (cachedReleases == null) {
                emit(Resource.Error("Failed to load new releases"))
            }
        }
    }

    /**
     * Gather unique artists from multiple sources (matching desktop's gatherNewReleasesArtists):
     * 1. Library tracks (distinct artists)
     * 2. Last.fm top artists
     * 3. ListenBrainz top artists
     *
     * Deduplicates by lowercased name, shuffles, caps at MAX_ARTISTS_TO_CHECK.
     */
    private suspend fun gatherArtists(): List<ArtistSource> {
        val seen = mutableSetOf<String>()
        val artists = mutableListOf<ArtistSource>()

        // Source 1: Library artists
        try {
            val libraryTracks = trackDao.getRecentSync(200)
            val libraryArtists = libraryTracks
                .map { it.artist }
                .distinct()
                .filter { seen.add(it.lowercase().trim()) }
                .map { ArtistSource(name = it, source = "library") }
            artists.addAll(libraryArtists.shuffled())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get library artists", e)
        }

        // Source 2: Last.fm top artists
        try {
            val lfmUsername = settingsStore.getLastFmUsername()
            if (lfmUsername != null) {
                val apiKey = BuildConfig.LASTFM_API_KEY
                val topArtists = lastFmApi.getUserTopArtists(
                    user = lfmUsername, apiKey = apiKey, limit = 50, period = "6month",
                )
                val lfmArtists = topArtists.topartists?.artist
                    ?.map { it.name }
                    ?.filter { seen.add(it.lowercase().trim()) }
                    ?.map { ArtistSource(name = it, source = "history") }
                    ?: emptyList()
                artists.addAll(lfmArtists.shuffled())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Last.fm top artists", e)
        }

        // Source 3: ListenBrainz top artists
        try {
            val lbUsername = settingsStore.getListenBrainzUsername()
            if (lbUsername != null) {
                val lbArtists = listenBrainzApi.getUserTopArtists(lbUsername, "half_yearly", 50)
                    .map { it.name }
                    .filter { seen.add(it.lowercase().trim()) }
                    .map { ArtistSource(name = it, source = "history") }
                artists.addAll(lbArtists.shuffled())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ListenBrainz top artists", e)
        }

        Log.d(TAG, "Gathered ${artists.size} unique artists, checking ${minOf(artists.size, MAX_ARTISTS_TO_CHECK)}")
        return artists.take(MAX_ARTISTS_TO_CHECK)
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
    ): List<FreshDrop> {
        // Get or lookup MBID
        val mbid = mbidCache[artistName.lowercase()]
            ?: run {
                val results = musicBrainzApi.searchArtists(artistName, limit = 1)
                val artist = results.artists.firstOrNull() ?: return emptyList()
                mbidCache[artistName.lowercase()] = artist.id
                artist.id
            }

        // Browse release-groups
        val response = musicBrainzApi.browseReleaseGroups(mbid, limit = 100)
        val cutoffStr = cutoffDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        return response.releaseGroups
            .filter { rg ->
                // Must have a release date
                val date = rg.firstReleaseDate ?: return@filter false
                // Must be after cutoff (last 6 months)
                date >= cutoffStr
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

/**
 * A new release (fresh drop).
 */
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
