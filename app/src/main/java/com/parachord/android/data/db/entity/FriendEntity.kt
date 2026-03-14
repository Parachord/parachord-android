package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted friend record, mirroring the desktop app's friend object.
 * Stores friend metadata and cached "now playing" track info.
 */
@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val id: String,
    val username: String,
    val service: String, // "lastfm" | "listenbrainz"
    val displayName: String,
    val avatarUrl: String? = null,
    val addedAt: Long, // epoch millis
    val lastFetchedAt: Long = 0,
    // Cached most recent / now-playing track
    val cachedTrackName: String? = null,
    val cachedTrackArtist: String? = null,
    val cachedTrackAlbum: String? = null,
    val cachedTrackTimestamp: Long = 0,
    val cachedTrackArtworkUrl: String? = null,
    /** Whether the friend is pinned to the sidebar. */
    val pinnedToSidebar: Boolean = false,
    /** Whether the pin was automatic (on-air auto-pin). Auto-pins get auto-unpinned. */
    val autoPinned: Boolean = false,
) {
    /** Friend is "on air" if their cached track was played within the last 10 minutes. */
    val isOnAir: Boolean
        get() = cachedTrackTimestamp > 0 &&
            (System.currentTimeMillis() / 1000 - cachedTrackTimestamp) < 600
}
