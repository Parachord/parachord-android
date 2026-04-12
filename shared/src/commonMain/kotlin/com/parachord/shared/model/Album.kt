package com.parachord.shared.model

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    val addedAt: Long = 0L,
    val spotifyId: String? = null,
)
