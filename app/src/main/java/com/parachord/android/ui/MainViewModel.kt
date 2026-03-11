package com.parachord.android.ui

import androidx.lifecycle.ViewModel
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    init {
        playbackController.connect()
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.release()
    }
}
