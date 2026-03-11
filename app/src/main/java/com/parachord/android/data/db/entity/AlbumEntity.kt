package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
)
