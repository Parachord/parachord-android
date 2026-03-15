package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Long? = null,
    val artworkUrl: String? = null,
    val sourceType: String? = null,
    val sourceUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Resolver that produced this source (e.g. "spotify", "soundcloud", "localfiles"). */
    val resolver: String? = null,
    /** Spotify URI for App Remote playback (e.g. "spotify:track:6rqhFgbbKwnb9MLmUQDhG6"). */
    val spotifyUri: String? = null,
    /** SoundCloud track ID for streaming via their API. */
    val soundcloudId: String? = null,
    /** Spotify track ID (e.g. "6rqhFgbbKwnb9MLmUQDhG6"). */
    val spotifyId: String? = null,
    /** Apple Music catalog song ID (e.g. "1440935467"). */
    val appleMusicId: String? = null,
) {
    /**
     * Derive all available resolvers from stored IDs.
     * Returns resolver names for which this track has a usable ID,
     * sorted by the provided resolver order (user-configured priority).
     */
    fun availableResolvers(resolverOrder: List<String> = emptyList()): List<String> {
        val available = buildList {
            if (!spotifyId.isNullOrBlank() || !spotifyUri.isNullOrBlank()) add("spotify")
            if (!appleMusicId.isNullOrBlank()) add("applemusic")
            if (!soundcloudId.isNullOrBlank()) add("soundcloud")
            // Include the stored resolver even if we don't have a specific ID field for it
            // (e.g. youtube, bandcamp, localfiles)
            if (resolver != null && !contains(resolver)) add(resolver)
        }
        if (resolverOrder.isEmpty()) return available
        return available.sortedBy { r ->
            val idx = resolverOrder.indexOf(r)
            if (idx == -1) resolverOrder.size else idx
        }
    }
}
