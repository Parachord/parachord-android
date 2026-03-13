package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    /** "artist", "album", "track" */
    val resultType: String? = null,
    val resultName: String? = null,
    val resultArtist: String? = null,
    val artworkUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
