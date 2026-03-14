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
)
