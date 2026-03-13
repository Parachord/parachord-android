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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadataService: MetadataService,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
) : ViewModel() {

    companion object {
        private const val TAG = "AlbumViewModel"
    }

    // Navigation already decodes URI path segments — do not double-decode
    private val albumTitle: String = savedStateHandle.get<String>("albumTitle") ?: ""
    private val artistName: String = savedStateHandle.get<String>("artistName") ?: ""

    private val _albumDetail = MutableStateFlow<AlbumDetail?>(null)
    val albumDetail: StateFlow<AlbumDetail?> = _albumDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    init {
        if (albumTitle.isNotBlank()) {
            loadAlbum()
        }
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _albumDetail.value = metadataService.getAlbumTracks(albumTitle, artistName)
            } catch (_: Exception) {
                // partial results still shown
            } finally {
                _isLoading.value = false
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

                val sources = resolverManager.resolveWithHints(
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
                        val s = resolverManager.resolveWithHints(query = q, spotifyId = t.spotifyId)
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

    fun playAll() {
        val detail = _albumDetail.value ?: return
        val tracks = detail.tracks
        if (tracks.isEmpty()) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val entities = tracks.mapNotNull { track ->
                    val query = "${track.artist} - ${track.title}"
                    val sources = resolverManager.resolveWithHints(
                        query = query,
                        spotifyId = track.spotifyId,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    track.toTrackEntity(detail, best)
                }
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
)
