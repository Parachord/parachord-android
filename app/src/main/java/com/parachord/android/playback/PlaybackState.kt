package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity

/** Represents the current playback state exposed to the UI layer. */
data class PlaybackState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val queue: List<TrackEntity> = emptyList(),
    val queueIndex: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

enum class RepeatMode { OFF, ALL, ONE }
