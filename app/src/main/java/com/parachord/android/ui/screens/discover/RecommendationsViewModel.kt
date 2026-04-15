package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.android.data.repository.RecommendedArtist
import com.parachord.android.data.repository.RecommendedTrack
import com.parachord.android.data.repository.Resource
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.resolver.ResolverScoring.Companion.MIN_CONFIDENCE_THRESHOLD
import java.util.UUID
/**
 * ViewModel for the Recommendations screen.
 * Fetches recommended artists and tracks based on listening history.
 * Supports source filtering: All | Last.fm | ListenBrainz (matching desktop).
 * Resolves tracks through the resolver pipeline for content resolver badges.
 */
class RecommendationsViewModel constructor(
    private val recommendationsRepository: RecommendationsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "RecommendationsVM"
        /** Number of tracks to resolve before emitting a batch update. */
        private const val RESOLVE_BATCH_SIZE = 5
    }

    // Initialize from singleton cache immediately — avoids flash of Loading state
    private val _allArtists = MutableStateFlow<Resource<List<RecommendedArtist>>>(
        recommendationsRepository.cachedArtistsList?.let { Resource.Success(it) } ?: Resource.Loading
    )
    private val _allTracks = MutableStateFlow<Resource<List<RecommendedTrack>>>(
        recommendationsRepository.cachedTracksList?.let { Resource.Success(it) } ?: Resource.Loading
    )

    /** Current source filter: "all", "lastfm", or "listenbrainz" */
    private val _sourceFilter = MutableStateFlow("all")
    val sourceFilter: StateFlow<String> = _sourceFilter

    /** Filtered artists based on current source filter */
    val recommendedArtists: StateFlow<Resource<List<RecommendedArtist>>> = MutableStateFlow<Resource<List<RecommendedArtist>>>(Resource.Loading)

    /** Filtered tracks based on current source filter */
    val recommendedTracks: StateFlow<Resource<List<RecommendedTrack>>> = MutableStateFlow<Resource<List<RecommendedTrack>>>(Resource.Loading)

    /** Track counts per source for filter chip labels */
    private val _sourceCounts = MutableStateFlow(SourceCounts())
    val sourceCounts: StateFlow<SourceCounts> = _sourceCounts

    /** Active resolution job — cancelled when a new batch arrives. */
    private var resolveJob: Job? = null

    init {
        // Set up filtered flows
        viewModelScope.launch {
            combine(_allArtists, _sourceFilter) { artists, filter ->
                when (artists) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> artists
                    is Resource.Success -> {
                        val filtered = if (filter == "all") {
                            artists.data
                        } else {
                            artists.data.filter { it.source == filter }
                        }
                        Resource.Success(filtered)
                    }
                }
            }.collect { (recommendedArtists as MutableStateFlow).value = it }
        }
        viewModelScope.launch {
            combine(_allTracks, _sourceFilter) { tracks, filter ->
                when (tracks) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> tracks
                    is Resource.Success -> {
                        val filtered = if (filter == "all") {
                            tracks.data
                        } else {
                            tracks.data.filter { it.source == filter }
                        }
                        Resource.Success(filtered)
                    }
                }
            }.collect { (recommendedTracks as MutableStateFlow).value = it }
        }

        loadRecommendations()
    }

    fun setSourceFilter(filter: String) {
        _sourceFilter.value = filter
    }

    fun refresh() {
        loadRecommendations()
    }

    /** Called on screen resume — reloads recommendations. */
    fun refreshIfStale() {
        loadRecommendations()
    }

    /**
     * Play a recommended track and populate the queue with the remaining
     * tracks in the filtered list — same pattern as library, playlists, etc.
     *
     * Tracks are added as metadata-only [TrackEntity] objects (no resolver data).
     * [PlaybackController.playTrackInternal] resolves each track on-the-fly
     * via the resolver pipeline when it's time to play, and
     * [TrackResolverCache.resolveInBackground] pre-resolves upcoming tracks
     * so resolver badges appear and playback starts instantly.
     */
    fun playTrack(track: RecommendedTrack) {
        val allTracks = (recommendedTracks.value as? Resource.Success)?.data ?: return
        val index = allTracks.indexOf(track).coerceAtLeast(0)

        val entities = allTracks.map { t ->
            TrackEntity(
                id = "rec-${t.title.hashCode()}-${t.artist.hashCode()}",
                title = t.title,
                artist = t.artist,
                album = t.album,
                duration = t.duration,
                artworkUrl = t.artworkUrl,
            )
        }

        playbackController.playQueue(
            entities,
            startIndex = index,
            context = PlaybackContext(type = "recommendations", name = "Recommended Songs"),
        )
    }

    // ── Artist actions ─────────────────────────────────────────────

    fun playArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
                if (topTracks.isEmpty()) return@launch
                val entities = topTracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "top-${track.title.hashCode()}-${track.artist.hashCode()}",
                        title = track.title, artist = track.artist, album = track.album,
                        duration = track.duration, artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) {
                    playbackController.playQueue(
                        entities, startIndex = 0,
                        context = PlaybackContext(type = "artist", name = artistName),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play top songs for '$artistName'", e)
            }
        }
    }

    fun queueArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
                if (topTracks.isEmpty()) return@launch
                val entities = topTracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "top-${track.title.hashCode()}-${track.artist.hashCode()}",
                        title = track.title, artist = track.artist, album = track.album,
                        duration = track.duration, artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) playbackController.addToQueue(entities)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue top songs for '$artistName'", e)
            }
        }
    }

    fun toggleArtistCollection(artistName: String, imageUrl: String?, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                libraryRepository.deleteArtistByName(artistName)
            } else {
                libraryRepository.addArtist(
                    ArtistEntity(
                        id = "manual-${UUID.randomUUID()}",
                        name = artistName,
                        imageUrl = imageUrl,
                    )
                )
            }
        }
    }

    private fun loadRecommendations() {
        viewModelScope.launch {
            recommendationsRepository.getRecommendedArtists().collect {
                _allArtists.value = it
            }
        }
        viewModelScope.launch {
            recommendationsRepository.getRecommendedTracks().collect { resource ->
                _allTracks.value = resource
                // Update source counts when tracks change
                if (resource is Resource.Success) {
                    val tracks = resource.data
                    _sourceCounts.value = SourceCounts(
                        total = tracks.size,
                        lastfm = tracks.count { it.source == "lastfm" },
                        listenbrainz = tracks.count { it.source == "listenbrainz" },
                    )
                    // Cancel any in-flight resolution and start fresh
                    resolveJob?.cancel()
                    resolveJob = resolveTracksProgressively(tracks)
                }
            }
        }
    }

    /**
     * Resolve recommendation tracks through the content resolver pipeline.
     * This gives us actual playback source badges (e.g. Spotify, YouTube)
     * rather than metadata source labels (Last.fm, ListenBrainz).
     * Batches updates to avoid per-track recomposition jank.
     */
    private fun resolveTracksProgressively(tracks: List<RecommendedTrack>): Job {
        return viewModelScope.launch {
            val mutableTracks = tracks.toMutableList()
            var pendingUpdates = 0
            for ((index, track) in tracks.withIndex()) {
                if (track.resolvers.isNotEmpty()) continue // already resolved
                try {
                    val query = "${track.title} ${track.artist}"
                    val sources = resolverManager.resolve(
                        query,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    if (sources.isNotEmpty()) {
                        // Build confidence map and filter out noMatch sources (< threshold)
                        // from display, matching desktop's noMatch sentinel filtering.
                        val confidenceMap = sources.associate {
                            it.resolver to (it.confidence?.toFloat() ?: 1f)
                        }
                        val resolverNames = sources
                            .filter { (it.confidence ?: 0.0) >= MIN_CONFIDENCE_THRESHOLD }
                            .map { it.resolver }
                            .distinct()
                        mutableTracks[index] = track.copy(
                            resolvers = resolverNames,
                            resolverConfidences = confidenceMap,
                        )
                        pendingUpdates++
                        // Emit in batches to reduce recomposition churn
                        if (pendingUpdates >= RESOLVE_BATCH_SIZE) {
                            _allTracks.value = Resource.Success(mutableTracks.toList())
                            pendingUpdates = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve '${track.title}' by ${track.artist}", e)
                }
            }
            // Flush any remaining updates
            if (pendingUpdates > 0) {
                _allTracks.value = Resource.Success(mutableTracks.toList())
            }
        }
    }
}

data class SourceCounts(
    val total: Int = 0,
    val lastfm: Int = 0,
    val listenbrainz: Int = 0,
)
