package com.parachord.shared.model

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val spotifyId: String? = null,
    val genres: String? = null,
    val addedAt: Long = 0L,
)
