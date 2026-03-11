package com.parachord.android.ui.screens.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.AlbumDetail
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.playback.PlaybackController
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
                val resolvedTracks = tracks.map { it.toTrackEntity(detail) }
                playbackController.playQueue(resolvedTracks, startIndex = index)
            } catch (_: Exception) {
                // Failed to resolve — try single track
                val track = tracks[index]
                val resolved = metadataService.resolveTrack(track.title, track.artist)
                if (resolved != null) {
                    playbackController.playTrack(resolved.toTrackEntity(detail))
                }
            } finally {
                _isResolving.value = false
            }
        }
    }

    fun playAll() {
        playTrack(0)
    }
}

private fun TrackSearchResult.toTrackEntity(album: AlbumDetail) = TrackEntity(
    id = spotifyId ?: "resolved-${title.hashCode()}-${artist.hashCode()}",
    title = title,
    artist = artist,
    album = album.title,
    duration = duration,
    artworkUrl = artworkUrl ?: album.artworkUrl,
    sourceType = if (spotifyId != null) "spotify" else null,
    sourceUrl = previewUrl,
)
