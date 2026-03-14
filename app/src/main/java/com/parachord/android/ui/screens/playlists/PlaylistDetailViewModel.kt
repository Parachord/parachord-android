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
}
