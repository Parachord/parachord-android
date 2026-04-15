package com.parachord.shared.playback

/**
 * Represents the current playback state exposed to the UI layer.
 * Platform-specific implementations provide the actual track type.
 */
data class StreamingMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

enum class RepeatMode { OFF, ALL, ONE }
