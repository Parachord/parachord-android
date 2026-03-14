package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val spotifyId: String? = null,
    val snapshotId: String? = null,
    val lastModified: Long = 0L,
    val locallyModified: Boolean = false,
    /** Display name of the playlist owner (from Spotify). */
    val ownerName: String? = null,
)
