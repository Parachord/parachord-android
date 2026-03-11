package com.parachord.android.ui.screens.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.AlbumDetail
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadataService: MetadataService,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val albumTitle: String =
        URLDecoder.decode(savedStateHandle.get<String>("albumTitle") ?: "", "UTF-8")
    private val artistName: String =
        URLDecoder.decode(savedStateHandle.get<String>("artistName") ?: "", "UTF-8")

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
                // Resolve the clicked track through the .axe resolver pipeline
                val track = tracks[index]
                val query = "${track.artist} - ${track.title}"
                val sources = resolverManager.resolve(query)
                val best = resolverScoring.selectBest(sources) ?: return@launch

                // Build the full queue with this resolved source for the clicked track
                val entity = track.toTrackEntity(detail, best)
                playbackController.playTrack(entity)
            } catch (_: Exception) {
                // resolution failed
            } finally {
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
                // Resolve all tracks through the .axe resolver pipeline
                val entities = tracks.mapNotNull { track ->
                    val query = "${track.artist} - ${track.title}"
                    val sources = resolverManager.resolve(query)
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    track.toTrackEntity(detail, best)
                }
                if (entities.isNotEmpty()) {
                    playbackController.playQueue(entities, startIndex = 0)
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
