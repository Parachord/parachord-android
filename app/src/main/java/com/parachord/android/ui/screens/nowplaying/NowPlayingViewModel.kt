package com.parachord.android.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun skipNext() {
        playbackController.skipNext()
    }

    fun skipPrevious() {
        playbackController.skipPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun toggleShuffle() {
        playbackController.toggleShuffle()
    }
}
