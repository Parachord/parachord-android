package com.parachord.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.scanner.MediaScanner
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val mediaScanner: MediaScanner,
) : ViewModel() {

    val recentTracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .map { tracks -> tracks.sortedByDescending { it.addedAt }.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasLibrary: StateFlow<Boolean> = repository.getAllTracks()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val scanProgress: StateFlow<ScanProgress> = mediaScanner.progress

    fun playTrack(track: TrackEntity) {
        val tracks = recentTracks.value
        val index = tracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(tracks, startIndex = index)
    }

    fun scanLocalMusic() {
        viewModelScope.launch {
            mediaScanner.scan()
        }
    }
}
