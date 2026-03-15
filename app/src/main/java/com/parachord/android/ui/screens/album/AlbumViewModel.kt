package com.parachord.android.ui.screens.album

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.AlbumDetail
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadataService: MetadataService,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val libraryRepository: com.parachord.android.data.repository.LibraryRepository,
    private val playbackStateHolder: com.parachord.android.playback.PlaybackStateHolder,
    private val trackResolverCache: TrackResolverCache,
) : ViewModel() {

    companion object {
        private const val TAG = "AlbumViewModel"
    }

    /** Title of the currently playing track (for highlight). */
    val nowPlayingTitle: StateFlow<String?> = playbackStateHolder.state
        .map { it.currentTrack?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Navigation already decodes URI path segments — do not double-decode
    private val albumTitle: String = savedStateHandle.get<String>("albumTitle") ?: ""
    private val artistName: String = savedStateHandle.get<String>("artistName") ?: ""

    private val _albumDetail = MutableStateFlow<AlbumDetail?>(null)
    val albumDetail: StateFlow<AlbumDetail?> = _albumDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    /** Cached resolved sources for tracks, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /**
     * Resolver badge names for UI display — sorted by user priority.
     * Uses shared cache (already sorted) merged with local album-specific results.
     */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers

    init {
        if (albumTitle.isNotBlank()) {
            loadAlbum()
        }
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Check local DB for cached artwork (available instantly from collection)
                val cachedArtwork = libraryRepository.getAlbumByTitleAndArtist(albumTitle, artistName)
                    ?.artworkUrl

                val detail = metadataService.getAlbumTracks(albumTitle, artistName)
                // Use local DB artwork as fallback if providers didn't return any
                _albumDetail.value = if (detail != null && detail.artworkUrl == null && cachedArtwork != null) {
                    detail.copy(artworkUrl = cachedArtwork)
                } else {
                    detail
                }
                _albumDetail.value?.tracks?.let { resolveTracksInBackground(it) }
            } catch (_: Exception) {
                // partial results still shown
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun resolveTracksInBackground(tracks: List<TrackSearchResult>) {
        viewModelScope.launch {
            for (track in tracks) {
                val key = trackKey(track.title, track.artist)
                if (_trackSources.value.containsKey(key)) continue
                // Check shared cache first (cross-context dedup)
                val cached = trackResolverCache.getSources(track.title, track.artist)
                if (cached != null) {
                    _trackSources.value = _trackSources.value + (key to cached)
                    continue
                }
                try {
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.title} ${track.artist}",
                        spotifyId = track.spotifyId,
                    )
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                        // Feed into shared cache for cross-context display + priority sorting
                        trackResolverCache.putSources(track.title, track.artist, sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    fun playTrack(index: Int) {
        val detail = _albumDetail.value ?: return
        val tracks = detail.tracks
        if (index !in tracks.indices) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                // 1. Resolve and play the clicked track immediately
                val track = tracks[index]
                val query = "${track.artist} - ${track.title}"
                Log.d(TAG, "Playing track: '$query' (spotifyId=${track.spotifyId})")

                val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                val sources = _trackSources.value[key]
                    ?: resolverManager.resolveWithHints(
                        query = query,
                        spotifyId = track.spotifyId,
                    )
                Log.d(TAG, "Resolved ${sources.size} sources: ${sources.map { "${it.resolver}(${it.confidence})" }}")

                val best = resolverScoring.selectBest(sources)
                if (best == null) {
                    Log.w(TAG, "No playable source found for '$query'")
                    return@launch
                }
                Log.d(TAG, "Selected: ${best.resolver} uri=${best.spotifyUri}")

                val entity = track.toTrackEntity(detail, best)
                playbackController.playTrack(entity)
                _isResolving.value = false

                // 2. Resolve remaining tracks in background and add to queue
                val remaining = tracks.subList(index + 1, tracks.size)
                if (remaining.isNotEmpty()) {
                    val context = PlaybackContext(type = "album", name = detail.title)
                    val entities = remaining.mapNotNull { t ->
                        val q = "${t.artist} - ${t.title}"
                        val k = "${t.title.lowercase().trim()}|${t.artist.lowercase().trim()}"
                        val s = _trackSources.value[k]
                            ?: resolverManager.resolveWithHints(query = q, spotifyId = t.spotifyId)
                        val b = resolverScoring.selectBest(s) ?: return@mapNotNull null
                        t.toTrackEntity(detail, b)
                    }
                    if (entities.isNotEmpty()) {
                        playbackController.addToQueue(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
                _isResolving.value = false
            }
        }
    }

    /** Get all playlists for the playlist picker. */
    val playlists: StateFlow<List<com.parachord.android.data.db.entity.PlaylistEntity>> =
        libraryRepository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Build a resolved TrackEntity from a track at [index] for context menu actions.
     * Uses cached sources if available.
     */
    /**
     * Build a TrackEntity from a track at [index] for context menu actions.
     * Uses cached sources if available. Not suspend — uses synchronous source cache only.
     */
    fun resolvedTrackEntity(index: Int): TrackEntity? {
        val detail = _albumDetail.value ?: return null
        val track = detail.tracks.getOrNull(index) ?: return null
        val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
        val sources = _trackSources.value[key]
        // Build entity without waiting for resolution — best effort from cache
        val best = sources?.firstOrNull()
        return TrackEntity(
            id = "album-${track.title.hashCode()}-${track.artist.hashCode()}",
            title = track.title,
            artist = track.artist,
            album = detail.title,
            duration = track.duration,
            artworkUrl = track.artworkUrl ?: detail.artworkUrl,
            sourceType = best?.sourceType,
            sourceUrl = best?.url,
            resolver = best?.resolver,
            spotifyUri = best?.spotifyUri,
            spotifyId = best?.spotifyId,
            soundcloudId = best?.soundcloudId,
            appleMusicId = best?.appleMusicId,
        )
    }

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: com.parachord.android.data.db.entity.PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(playlist.id, listOf(track))
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTrack(track)
        }
    }

    fun playAll() {
        val detail = _albumDetail.value ?: return
        val tracks = detail.tracks
        if (tracks.isEmpty()) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val entities = resolveAllTracks(detail)
                if (entities.isNotEmpty()) {
                    val context = PlaybackContext(type = "album", name = detail.title)
                    playbackController.playQueue(entities, startIndex = 0, context = context)
                }
            } catch (_: Exception) {
                // resolution failed
            } finally {
                _isResolving.value = false
            }
        }
    }

    /** Add all album tracks to the queue without interrupting playback. */
    fun queueAll() {
        val detail = _albumDetail.value ?: return
        val tracks = detail.tracks
        if (tracks.isEmpty()) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val entities = resolveAllTracks(detail)
                if (entities.isNotEmpty()) {
                    playbackController.addToQueue(entities)
                }
            } catch (_: Exception) {
                // resolution failed
            } finally {
                _isResolving.value = false
            }
        }
    }

    private suspend fun resolveAllTracks(detail: AlbumDetail): List<TrackEntity> =
        detail.tracks.mapNotNull { track ->
            val query = "${track.artist} - ${track.title}"
            val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
            val sources = _trackSources.value[key]
                ?: resolverManager.resolveWithHints(
                    query = query,
                    spotifyId = track.spotifyId,
                )
            val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
            track.toTrackEntity(detail, best)
        }
}

private fun TrackSearchResult.toTrackEntity(
    album: AlbumDetail,
    source: ResolvedSource,
) = TrackEntity(
    id = "resolved-${title.hashCode()}-${artist.hashCode()}-${album.title.hashCode()}",
    title = title,
    artist = artist,
    album = album.title,
    duration = duration,
    artworkUrl = artworkUrl ?: album.artworkUrl,
    sourceType = source.sourceType,
    sourceUrl = source.url,
    resolver = source.resolver,
    spotifyUri = source.spotifyUri,
    soundcloudId = source.soundcloudId,
    appleMusicId = source.appleMusicId,
)
