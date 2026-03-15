package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table linking playlists to tracks with ordering.
 * Mirrors the desktop's inline tracks array but using a relational model.
 *
 * Cascade delete: removing a playlist removes all its track associations
 * (but NOT the tracks themselves from the tracks table).
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    /** Track data stored inline (not referencing tracks table — playlist tracks
     *  are independent of the Collection). */
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String? = null,
    val trackDuration: Long? = null,
    val trackArtworkUrl: String? = null,
    val trackSourceUrl: String? = null,
    val trackResolver: String? = null,
    val trackSpotifyUri: String? = null,
    val trackSoundcloudId: String? = null,
    val trackSpotifyId: String? = null,
    val trackAppleMusicId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)
