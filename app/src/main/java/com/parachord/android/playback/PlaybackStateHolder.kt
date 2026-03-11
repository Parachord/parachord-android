package com.parachord.android.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared holder for playback state, observable by ViewModels and updated
 * by PlaybackController. Acts as the single source of truth for UI playback state.
 *
 * ViewModels should call PlaybackController for actions (play, pause, skip).
 * PlaybackController calls [update] to push ExoPlayer state back here.
 */
@Singleton
class PlaybackStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun update(transform: PlaybackState.() -> PlaybackState) {
        _state.update { it.transform() }
    }
}
