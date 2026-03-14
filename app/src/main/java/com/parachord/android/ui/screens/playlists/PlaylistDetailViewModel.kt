package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistDao: PlaylistDao,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val playlistId: String = savedStateHandle["playlistId"] ?: ""

    val playlist: StateFlow<PlaylistEntity?> = playlistDao.getByIdFlow(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tracks: StateFlow<List<PlaylistTrackEntity>> =
        libraryRepository.getPlaylistTracks(playlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Play all tracks starting from the given index. */
    fun playAll(startIndex: Int = 0) {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        playbackController.playQueue(entities, startIndex)
    }

    /** Play a single track from the playlist. */
    fun playTrack(index: Int) {
        playAll(startIndex = index)
    }

    /** Get all playlists for the playlist picker. */
    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Context menu actions ─────────────────────────────────────────

    fun trackEntityAt(index: Int): TrackEntity? {
        val pt = tracks.value.getOrNull(index) ?: return null
        return libraryRepository.playlistTrackToTrackEntity(pt)
    }

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(targetPlaylist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(targetPlaylist.id, listOf(track))
        }
    }

    fun removeFromPlaylist(position: Int) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, position)
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTrack(track)
        }
    }

    /** Add all playlist tracks to the queue without interrupting playback. */
    fun queueAll() {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        playbackController.addToQueue(entities)
    }

    /** Delete this playlist. */
    fun deletePlaylist() {
        viewModelScope.launch {
            playlist.value?.let { libraryRepository.deletePlaylist(it) }
        }
    }
}
