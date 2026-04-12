package com.parachord.shared.playback

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackContext(
    val type: String,
    val name: String,
    val id: String? = null,
)
