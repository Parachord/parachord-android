package com.parachord.android.data.repository

import android.util.Log
import com.parachord.android.data.api.ListenBrainzApi
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class RecommendationsRepository @Inject constructor(
    private val listenBrainzApi: ListenBrainzApi,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "RecommendationsRepo"
    }

    /**
     * Get recommended tracks from Last.fm and ListenBrainz.
     * Mirrors desktop's parallel fetch + merge approach.
     */
    fun getRecommendedTracks(): Flow<Resource<List<RecommendedTrack>>> = flow {
        emit(Resource.Loading)
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
        emit(Resource.Loading)
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

            // Extract unique artists
            val seenArtists = mutableSetOf<String>()
            val artists = allTracks.mapNotNull { track ->
                val key = track.artist.lowercase().trim()
                if (seenArtists.add(key)) {
                    RecommendedArtist(
                        name = track.artist,
                        source = track.source,
                    )
                } else null
            }

            // Emit immediately without images so UI shows artist names right away
            emit(Resource.Success(artists))

            // Progressively resolve artist images (matching desktop's sequential approach).
            // Each image fetch triggers a new emission so artists appear with images one-by-one.
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
                        emit(Resource.Success(mutableArtists.toList()))
                    }
                } catch (_: Exception) { /* skip */ }
            }
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
            val playlistTracks = listenBrainzApi.getRecommendationPlaylistTracks(username)
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
            val topRecordings = listenBrainzApi.getUserTopRecordings(username, "month", 50)
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
