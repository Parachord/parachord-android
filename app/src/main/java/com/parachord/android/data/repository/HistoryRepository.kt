package com.parachord.android.data.repository

import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs

/**
 * Repository for listening history data, fetching from Last.fm and ListenBrainz.
 * Mirrors the desktop app's history page data sources.
 */
class HistoryRepository constructor(
    private val lastFmApi: LastFmApi,
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
) {
    companion object {
        private const val TAG = "HistoryRepository"
        private const val DEDUPE_WINDOW_SECONDS = 300 // 5 minutes
    }

    /**
     * Fetch user's top tracks from Last.fm for the given period.
     * Enriches tracks missing artwork by looking up album art via Cover Art Archive / Last.fm / Spotify.
     */
    fun getTopTracks(period: String, limit: Int = 50): Flow<Resource<List<HistoryTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val username = settingsStore.getLastFmUsername()
            if (username == null) {
                emit(Resource.Error("Connect Last.fm to view your top tracks"))
                return@flow
            }

            val response = lastFmApi.getUserTopTracks(
                user = username,
                period = period,
                limit = limit,
                apiKey = BuildConfig.LASTFM_API_KEY,
            )

            val tracks = response.toptracks?.track?.map { track ->
                HistoryTrack(
                    title = track.name,
                    artist = track.artist?.name ?: "",
                    artworkUrl = track.image.bestImageUrl(),
                    playCount = track.playcount?.toIntOrNull() ?: 0,
                    rank = track.attr?.rank?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()

            // Emit initial results immediately (may be missing artwork)
            emit(Resource.Success(tracks))

            // Enrich tracks missing artwork via MetadataService (Cover Art Archive → Last.fm → Spotify)
            val tracksNeedingArt = tracks.filter { it.artworkUrl == null }
            if (tracksNeedingArt.isNotEmpty()) {
                val enriched = enrichTrackArtwork(tracks)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top tracks", e)
            emit(Resource.Error("Failed to load top tracks"))
        }
    }

    /**
     * Enrich tracks missing artwork by searching via MetadataService.
     * Groups by artist+title and looks up artwork concurrently.
     */
    private suspend fun enrichTrackArtwork(tracks: List<HistoryTrack>): List<HistoryTrack> = coroutineScope {
        // Build a cache of artwork URLs from search results
        val artworkCache = mutableMapOf<String, String>()

        // Batch-lookup missing artwork concurrently (limit to 15 concurrent)
        val tracksNeedingArt = tracks.filter { it.artworkUrl == null }.take(15)
        tracksNeedingArt.map { track ->
            async {
                try {
                    val results = metadataService.searchTracks("${track.artist} ${track.title}", limit = 1)
                    val artwork = results.firstOrNull()?.artworkUrl
                    if (artwork != null) {
                        val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
                        synchronized(artworkCache) { artworkCache[key] = artwork }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        // Apply cached artwork to tracks
        tracks.map { track ->
            if (track.artworkUrl != null) return@map track
            val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
            val enrichedUrl = artworkCache[key]
            if (enrichedUrl != null) track.copy(artworkUrl = enrichedUrl) else track
        }
    }

    /**
     * Fetch user's top albums from Last.fm for the given period.
     */
    fun getTopAlbums(period: String, limit: Int = 50): Flow<Resource<List<HistoryAlbum>>> = flow {
        emit(Resource.Loading)
        try {
            val username = settingsStore.getLastFmUsername()
            if (username == null) {
                emit(Resource.Error("Connect Last.fm to view your top albums"))
                return@flow
            }

            val response = lastFmApi.getUserTopAlbums(
                user = username,
                period = period,
                limit = limit,
                apiKey = BuildConfig.LASTFM_API_KEY,
            )

            val albums = response.topalbums?.album?.map { album ->
                HistoryAlbum(
                    name = album.name,
                    artist = album.artist?.name ?: "",
                    artworkUrl = album.image.bestImageUrl(),
                    playCount = album.playcount?.toIntOrNull() ?: 0,
                    rank = album.attr?.rank?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()

            emit(Resource.Success(albums))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top albums", e)
            emit(Resource.Error("Failed to load top albums"))
        }
    }

    /**
     * Fetch user's top artists from Last.fm for the given period.
     * Last.fm deprecated artist images in 2020, so we resolve images from Spotify
     * via MetadataService, matching the desktop's resolveTopArtistImages().
     */
    fun getTopArtists(period: String, limit: Int = 50): Flow<Resource<List<HistoryArtist>>> = flow {
        emit(Resource.Loading)
        try {
            val username = settingsStore.getLastFmUsername()
            if (username == null) {
                emit(Resource.Error("Connect Last.fm to view your top artists"))
                return@flow
            }

            val response = lastFmApi.getUserTopArtists(
                user = username,
                period = period,
                limit = limit,
                apiKey = BuildConfig.LASTFM_API_KEY,
            )

            val artists = response.topartists?.artist?.map { artist ->
                HistoryArtist(
                    name = artist.name,
                    imageUrl = artist.image.bestImageUrl(), // Almost always null (deprecated)
                    playCount = artist.playcount?.toIntOrNull() ?: 0,
                    rank = artist.attr?.rank?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()

            // Emit initial results immediately (likely without images)
            emit(Resource.Success(artists))

            // Resolve artist images from Spotify via MetadataService
            // Matches desktop's resolveTopArtistImages() pattern
            val artistsNeedingImages = artists.filter { it.imageUrl == null }
            if (artistsNeedingImages.isNotEmpty()) {
                val enriched = enrichArtistImages(artists)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top artists", e)
            emit(Resource.Error("Failed to load top artists"))
        }
    }

    /**
     * Resolve artist images from Spotify via MetadataService.
     * Mirrors desktop's resolveTopArtistImages() which calls getArtistImage() for each artist.
     */
    private suspend fun enrichArtistImages(artists: List<HistoryArtist>): List<HistoryArtist> = coroutineScope {
        val imageCache = mutableMapOf<String, String>()

        // Batch-lookup missing images concurrently (limit to 15)
        artists.filter { it.imageUrl == null }.take(15).map { artist ->
            async {
                try {
                    val info = metadataService.getArtistInfo(artist.name)
                    val imageUrl = info?.imageUrl
                    if (imageUrl != null) {
                        synchronized(imageCache) { imageCache[artist.name.lowercase()] = imageUrl }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        artists.map { artist ->
            if (artist.imageUrl != null) return@map artist
            val enrichedUrl = imageCache[artist.name.lowercase()]
            if (enrichedUrl != null) artist.copy(imageUrl = enrichedUrl) else artist
        }
    }

    /**
     * Fetch recently played tracks from both Last.fm and ListenBrainz.
     * Deduplicates by artist+title within 5-minute windows, matching the desktop.
     */
    fun getRecentTracks(): Flow<Resource<List<RecentTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val allTracks = mutableListOf<RecentTrack>()

            coroutineScope {
                // Last.fm recent tracks
                val lastFmDeferred = async { fetchLastFmRecentTracks() }
                // ListenBrainz recent listens
                val lbDeferred = async { fetchListenBrainzRecentTracks() }

                allTracks.addAll(lastFmDeferred.await())
                allTracks.addAll(lbDeferred.await())
            }

            // Deduplicate: group by normalized artist+title, within 5-min windows
            val deduped = deduplicateRecentTracks(allTracks)

            // Sort by timestamp descending (most recent first)
            val sorted = deduped.sortedByDescending { it.timestamp }

            emit(Resource.Success(sorted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recent tracks", e)
            emit(Resource.Error("Failed to load recent tracks"))
        }
    }

    private suspend fun fetchLastFmRecentTracks(): List<RecentTrack> {
        val username = settingsStore.getLastFmUsername() ?: return emptyList()
        return try {
            val response = lastFmApi.getUserRecentTracks(
                user = username,
                limit = 50,
                apiKey = BuildConfig.LASTFM_API_KEY,
            )
            response.recenttracks?.track?.map { track ->
                RecentTrack(
                    title = track.name,
                    artist = track.artist?.name ?: "",
                    album = track.album?.name,
                    artworkUrl = track.image.bestImageUrl(),
                    timestamp = track.date?.uts?.toLongOrNull() ?: 0,
                    source = "Last.fm",
                    nowPlaying = track.attr?.nowplaying == "true",
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm recent tracks failed", e)
            emptyList()
        }
    }

    private suspend fun fetchListenBrainzRecentTracks(): List<RecentTrack> {
        val token = settingsStore.getListenBrainzToken() ?: return emptyList()
        val username = settingsStore.getListenBrainzUsername() ?: return emptyList()
        return try {
            val listens = listenBrainzClient.getRecentListens(username, token)
            listens.map { listen ->
                RecentTrack(
                    title = listen.trackName,
                    artist = listen.artistName,
                    album = listen.releaseName,
                    timestamp = listen.listenedAt,
                    source = "ListenBrainz",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ListenBrainz recent listens failed", e)
            emptyList()
        }
    }

    /**
     * Deduplicate tracks by normalized artist+title within 5-minute windows.
     * Prefers Last.fm entries (they have artwork).
     */
    private fun deduplicateRecentTracks(tracks: List<RecentTrack>): List<RecentTrack> {
        val result = mutableListOf<RecentTrack>()

        for (track in tracks.sortedByDescending { it.timestamp }) {
            val key = "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"
            val isDupe = result.any { existing ->
                val existingKey = "${existing.artist.lowercase().trim()}|${existing.title.lowercase().trim()}"
                existingKey == key && abs(existing.timestamp - track.timestamp) < DEDUPE_WINDOW_SECONDS
            }
            if (!isDupe) {
                result.add(track)
            }
        }

        return result
    }
}
