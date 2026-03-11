package com.parachord.android.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared holder for playback state, observable by ViewModels and updated
 * by PlaybackService. Acts as the single source of truth for UI playback state.
 */
@Singleton
class PlaybackStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun update(transform: PlaybackState.() -> PlaybackState) {
        _state.update { it.transform() }
    }

    fun togglePlayPause() {
        _state.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun skipNext() {
        _state.update { current ->
            val nextIndex = (current.queueIndex + 1).coerceAtMost(current.queue.lastIndex.coerceAtLeast(0))
            current.copy(
                queueIndex = nextIndex,
                currentTrack = current.queue.getOrNull(nextIndex) ?: current.currentTrack,
                position = 0L,
            )
        }
    }

    fun skipPrevious() {
        _state.update { current ->
            val prevIndex = (current.queueIndex - 1).coerceAtLeast(0)
            current.copy(
                queueIndex = prevIndex,
                currentTrack = current.queue.getOrNull(prevIndex) ?: current.currentTrack,
                position = 0L,
            )
        }
    }
}
