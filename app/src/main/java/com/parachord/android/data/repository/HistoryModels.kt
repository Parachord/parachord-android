package com.parachord.android.data.repository

/**
 * Domain models for the History page, matching the desktop app's history data structures.
 */

data class HistoryTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val playCount: Int = 0,
    val rank: Int = 0,
)

data class HistoryAlbum(
    val name: String,
    val artist: String,
    val artworkUrl: String? = null,
    val playCount: Int = 0,
    val rank: Int = 0,
)

data class HistoryArtist(
    val name: String,
    val imageUrl: String? = null,
    val playCount: Int = 0,
    val rank: Int = 0,
)

data class RecentTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val timestamp: Long = 0,
    val source: String = "",
    val nowPlaying: Boolean = false,
)
