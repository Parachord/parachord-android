package com.parachord.shared.model

data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val spotifyId: String? = null,
    val snapshotId: String? = null,
    val lastModified: Long = 0L,
    val locallyModified: Boolean = false,
    val ownerName: String? = null,
    val sourceUrl: String? = null,
    val sourceContentHash: String? = null,
    val localOnly: Boolean = false,
)
