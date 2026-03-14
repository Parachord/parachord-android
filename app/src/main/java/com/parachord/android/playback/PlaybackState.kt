package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity

/** Represents the current playback state exposed to the UI layer. */
data class PlaybackState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val upNext: List<TrackEntity> = emptyList(),
    val playbackContext: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val spinoffMode: Boolean = false,
    val spinoffLoading: Boolean = false,
    val spinoffAvailable: Boolean? = null, // null=unchecked, true/false=checked
)

enum class RepeatMode { OFF, ALL, ONE }
