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
)
