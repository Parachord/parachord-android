package com.parachord.shared.model

data class Friend(
    val id: String,
    val username: String,
    val service: String, // "lastfm" | "listenbrainz"
    val displayName: String,
    val avatarUrl: String? = null,
    val addedAt: Long,
    val lastFetchedAt: Long = 0,
    val cachedTrackName: String? = null,
    val cachedTrackArtist: String? = null,
    val cachedTrackAlbum: String? = null,
    val cachedTrackTimestamp: Long = 0,
    val cachedTrackArtworkUrl: String? = null,
    val pinnedToSidebar: Boolean = false,
    val autoPinned: Boolean = false,
) {
    /** Friend is "on air" if their cached track was played within the last 10 minutes. */
    val isOnAir: Boolean
        get() = cachedTrackTimestamp > 0 &&
            (currentTimeMillis() / 1000 - cachedTrackTimestamp) < 600
}

/** Platform-agnostic current time in milliseconds. */
internal expect fun currentTimeMillis(): Long
