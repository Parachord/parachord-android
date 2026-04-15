package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic playback engine interface.
 *
 * Defines the contract that both Android (ExoPlayer + Spotify Connect + Apple Music)
 * and iOS (AVPlayer + Spotify SDK + MusicKit) implementations must fulfill.
 *
 * The actual playback routing (which handler to use for each resolver type)
 * is platform-specific. This interface exposes the unified playback controls
 * and state that the shared UI/business logic layer needs.
 */
interface PlaybackEngine {

    /** Current playback state. */
    val isPlaying: StateFlow<Boolean>

    /** Current track position in milliseconds. */
    val position: StateFlow<Long>

    /** Current track duration in milliseconds. */
    val duration: StateFlow<Long>

    /** Whether shuffle mode is enabled. */
    val shuffleEnabled: StateFlow<Boolean>

    /** Current repeat mode. */
    val repeatMode: StateFlow<RepeatMode>

    // ── Playback Controls ──

    fun play(track: Track)
    fun pause()
    fun resume()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun stop()

    // ── Queue Controls ──

    fun setQueue(tracks: List<Track>, startIndex: Int, context: PlaybackContext? = null)
    fun addToQueue(tracks: List<Track>)
    fun insertNext(tracks: List<Track>)
    fun clearQueue()
    fun toggleShuffle()
    fun cycleRepeatMode()
}
