package com.parachord.android.data.metadata

import kotlinx.serialization.Serializable

/**
 * Unified metadata models returned by cascading providers.
 * These are UI-layer models, not database entities.
 */

@Serializable
data class ArtistInfo(
    val name: String,
    val mbid: String? = null,
    val imageUrl: String? = null,
    val bio: String? = null,
    val tags: List<String> = emptyList(),
    val similarArtists: List<String> = emptyList(),
    val provider: String = "",
)

@Serializable
data class TrackSearchResult(
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long? = null,
    val artworkUrl: String? = null,
    val previewUrl: String? = null,
    val spotifyId: String? = null,
    val mbid: String? = null,
    val provider: String = "",
)

@Serializable
data class AlbumSearchResult(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    val mbid: String? = null,
    val spotifyId: String? = null,
    val releaseType: String? = null,
    val provider: String = "",
)

@Serializable
data class AlbumDetail(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val tracks: List<TrackSearchResult> = emptyList(),
    val provider: String = "",
)
