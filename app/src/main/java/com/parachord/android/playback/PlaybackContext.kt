package com.parachord.android.playback

import kotlinx.serialization.Serializable

/**
 * Tags a queue with its source collection, matching the desktop's
 * `_playbackContext` pattern (e.g. `{type: 'album', name: 'Abbey Road'}`).
 */
@Serializable
data class PlaybackContext(
    val type: String,  // "album", "playlist", "artist", "search", etc.
    val name: String,  // "Abbey Road", "My Playlist", etc.
)
