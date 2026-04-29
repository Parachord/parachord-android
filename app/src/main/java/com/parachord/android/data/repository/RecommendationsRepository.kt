package com.parachord.android.data.repository

import android.content.Context
import com.parachord.shared.platform.Log
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Repository for music recommendations, mirroring the desktop app's approach.
 *
 * Strategy (matching desktop `loadRecommendations()`):
 * 1. Fetch pre-computed recommendations from Last.fm web endpoint
 * 2. Fetch ListenBrainz curated recommendation playlists (fallback: top recordings)
 * 3. Merge and deduplicate (ListenBrainz takes priority)
 * 4. Extract unique artists from the combined track list
 *
 * Both services are fetched in parallel; either can fail without blocking the other.
 */
class RecommendationsRepository constructor(
    private val context: Context,
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "RecommendationsRepo"
        private const val CACHE_FILE = "recommendations_cache.json"
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache for stale-while-revalidate. */
    private var cachedTracks: List<RecommendedTrack>? = null
    private var cachedArtists: List<RecommendedArtist>? = null
    private var diskCacheLoaded = false

    /** Synchronous access to cached data (for ViewModel initial state). */
    val cachedTracksList: List<RecommendedTrack>?
        get() {
            if (!diskCacheLoaded) loadDiskCache()
            return cachedTracks
        }
    val cachedArtistsList: List<RecommendedArtist>?
        get() {
            if (!diskCacheLoaded) loadDiskCache()
            return cachedArtists
        }

    private fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return
            val wrapper = diskJson.decodeFromString<RecommendationsDiskCache>(file.readText())
            cachedTracks = wrapper.tracks
            cachedArtists = wrapper.artists
            Log.d(TAG, "Loaded ${wrapper.tracks.size} tracks, ${wrapper.artists.size} artists from disk cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    private fun saveDiskCache() {
        try {
            val wrapper = RecommendationsDiskCache(
                tracks = cachedTracks ?: emptyList(),
                artists = cachedArtists ?: emptyList(),
            )
            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(diskJson.encodeToString(wrapper))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /**
     * Get recommended tracks from Last.fm and ListenBrainz.
     * Mirrors desktop's parallel fetch + merge approach.
     */
    fun getRecommendedTracks(): Flow<Resource<List<RecommendedTrack>>> = flow {
        if (!diskCacheLoaded) loadDiskCache()
        // Show stale cache immediately while refreshing
        if (cachedTracks != null) {
            emit(Resource.Success(cachedTracks!!))
        } else {
            emit(Resource.Loading)
        }
        try {
            val lastFmUsername = settingsStore.getLastFmUsername()
            val lbUsername = settingsStore.getListenBrainzUsername()

            if (lastFmUsername == null && lbUsername == null) {
                emit(Resource.Error("Connect Last.fm or ListenBrainz in Settings to get recommendations"))
                return@flow
            }

            val allTracks = mutableListOf<RecommendedTrack>()

            coroutineScope {
                // Fetch from both services in parallel
                val lastFmDeferred = async {
                    if (lastFmUsername != null) fetchLastFmRecommendations(lastFmUsername) else emptyList()
                }
                val lbDeferred = async {
                    if (lbUsername != null) fetchListenBrainzRecommendations(lbUsername) else emptyList()
                }

                // ListenBrainz first (higher priority in merge, matching desktop)
                allTracks.addAll(lbDeferred.await())
                allTracks.addAll(lastFmDeferred.await())
            }

            if (allTracks.isEmpty()) {
                emit(Resource.Error("No recommendations available yet.\nListen to more music to get personalized suggestions!"))
                return@flow
            }

            // Deduplicate by normalized artist+title
            val seen = mutableSetOf<String>()
            val deduped = allTracks.filter { track ->
                val key = "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"
                seen.add(key) // returns false if already present
            }

            Log.d(TAG, "Merged recommendations → ${deduped.size} unique tracks")
            cachedTracks = deduped
            saveDiskCache()
            emit(Resource.Success(deduped))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommendations", e)
            emit(Resource.Error("Failed to load recommendations"))
        }
    }

    /**
     * Get recommended artists extracted from the recommended tracks.
     * Mirrors desktop: unique artists from merged tracks, with progressive image loading.
     * Desktop's resolveRecommendationArtistImages() fetches images one-by-one and
     * updates state after each, so artists appear with images progressively.
     */
    fun getRecommendedArtists(): Flow<Resource<List<RecommendedArtist>>> = flow {
        if (!diskCacheLoaded) loadDiskCache()
        // Show stale cache immediately while refreshing
        if (cachedArtists != null) {
            emit(Resource.Success(cachedArtists!!))
        } else {
            emit(Resource.Loading)
        }
        try {
            val lastFmUsername = settingsStore.getLastFmUsername()
            val lbUsername = settingsStore.getListenBrainzUsername()

            if (lastFmUsername == null && lbUsername == null) {
                emit(Resource.Error("Connect Last.fm or ListenBrainz in Settings to get recommendations"))
                return@flow
            }

            val allTracks = mutableListOf<RecommendedTrack>()

            coroutineScope {
                val lastFmDeferred = async {
                    if (lastFmUsername != null) fetchLastFmRecommendations(lastFmUsername) else emptyList()
                }
                val lbDeferred = async {
                    if (lbUsername != null) fetchListenBrainzRecommendations(lbUsername) else emptyList()
                }
                allTracks.addAll(lbDeferred.await())
                allTracks.addAll(lastFmDeferred.await())
            }

            if (allTracks.isEmpty()) {
                emit(Resource.Error("No recommendations available yet.\nListen to more music to get personalized suggestions!"))
                return@flow
            }

            // Extract unique artists, carrying over images from cached data
            val cachedImagesByName = cachedArtists?.associateBy(
                { it.name.lowercase().trim() },
                { it.imageUrl },
            ) ?: emptyMap()

            val seenArtists = mutableSetOf<String>()
            val artists = allTracks.mapNotNull { track ->
                val key = track.artist.lowercase().trim()
                if (seenArtists.add(key)) {
                    RecommendedArtist(
                        name = track.artist,
                        source = track.source,
                        imageUrl = cachedImagesByName[key],
                    )
                } else null
            }

            cachedArtists = artists
            saveDiskCache()
            emit(Resource.Success(artists))

            // Progressively resolve images only for artists that still need them.
            val mutableArtists = artists.toMutableList()
            val toEnrich = mutableArtists.withIndex()
                .filter { it.value.imageUrl == null }
                .take(15)

            Log.d(TAG, "Fetching images for ${toEnrich.size} recommended artists...")
            for ((index, artist) in toEnrich) {
                try {
                    val info = withContext(Dispatchers.IO) {
                        metadataService.getArtistInfo(artist.name)
                    }
                    val imageUrl = info?.imageUrl
                    if (imageUrl != null) {
                        mutableArtists[index] = artist.copy(imageUrl = imageUrl)
                        cachedArtists = mutableArtists.toList()
                        emit(Resource.Success(mutableArtists.toList()))
                    }
                } catch (_: Exception) { /* skip */ }
            }
            // Save final enriched data to disk
            saveDiskCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommended artists", e)
            emit(Resource.Error("Failed to load recommendations"))
        }
    }

    /**
     * Fetch recommendations from Last.fm's web endpoint.
     * Endpoint: https://www.last.fm/player/station/user/{username}/recommended
     * This is a web API, not the official Last.fm API v2.
     */
    private suspend fun fetchLastFmRecommendations(username: String): List<RecommendedTrack> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://www.last.fm/player/station/user/$username/recommended"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body == null) {
                    Log.w(TAG, "Last.fm recommendations failed: ${response.code}")
                    return@withContext emptyList()
                }

                val json = JSONObject(body)
                val playlist = json.optJSONArray("playlist") ?: return@withContext emptyList()

                val tracks = mutableListOf<RecommendedTrack>()
                for (i in 0 until playlist.length()) {
                    try {
                        val item = playlist.getJSONObject(i)
                        val artistsList = item.optJSONArray("artists")
                        val artistName = artistsList?.optJSONObject(0)?.optString("name", "") ?: ""
                        val title = item.optString("name", "")
                        val duration = item.optLong("duration", 0)

                        if (title.isNotBlank() && artistName.isNotBlank()) {
                            tracks.add(
                                RecommendedTrack(
                                    title = title,
                                    artist = artistName,
                                    duration = if (duration > 0) duration else null,
                                    source = "lastfm",
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse Last.fm recommendation at $i", e)
                    }
                }

                Log.d(TAG, "Fetched ${tracks.size} Last.fm recommendations for $username")
                tracks
            } catch (e: Exception) {
                Log.w(TAG, "Last.fm recommendations failed", e)
                emptyList()
            }
        }

    /**
     * Fetch recommendations from ListenBrainz.
     * Primary: curated recommendation playlists.
     * Fallback: user's top recordings from the past month.
     */
    private suspend fun fetchListenBrainzRecommendations(username: String): List<RecommendedTrack> {
        return try {
            // Try recommendation playlists first
            val playlistTracks = listenBrainzClient.getRecommendationPlaylistTracks(username)
            if (playlistTracks.isNotEmpty()) {
                Log.d(TAG, "Fetched ${playlistTracks.size} ListenBrainz playlist recommendations for $username")
                return playlistTracks.map { track ->
                    RecommendedTrack(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        source = "listenbrainz",
                    )
                }
            }

            // Fallback: top recordings from past month
            Log.d(TAG, "No LB playlists, falling back to top recordings for $username")
            val topRecordings = listenBrainzClient.getUserTopRecordings(username, "month", 50)
            topRecordings.map { rec ->
                RecommendedTrack(
                    title = rec.trackName,
                    artist = rec.artistName,
                    album = rec.releaseName,
                    source = "listenbrainz",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ListenBrainz recommendations failed for $username", e)
            emptyList()
        }
    }

}

/** Disk cache wrapper for JSON serialization. */
@Serializable
private data class RecommendationsDiskCache(
    val tracks: List<RecommendedTrack>,
    val artists: List<RecommendedArtist>,
)
