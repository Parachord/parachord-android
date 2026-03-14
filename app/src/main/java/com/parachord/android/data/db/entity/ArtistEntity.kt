package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String? = null,
    val spotifyId: String? = null,
    val genres: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)
