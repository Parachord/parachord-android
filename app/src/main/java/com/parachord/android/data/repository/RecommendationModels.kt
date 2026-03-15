package com.parachord.android.data.repository

import kotlinx.serialization.Serializable

/**
 * Domain models for the Recommendations page.
 */

@Serializable
data class RecommendedArtist(
    val name: String,
    val imageUrl: String? = null,
    val reason: String? = null,
    val source: String = "", // "lastfm", "listenbrainz"
)

@Serializable
data class RecommendedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val duration: Long? = null,
    val source: String = "", // metadata source: "lastfm", "listenbrainz" — NOT a content resolver
    val resolvers: List<String> = emptyList(), // content resolvers: "spotify", "youtube", etc.
)
