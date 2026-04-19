package com.parachord.android.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Wrapper for the public Smart Links Cloudflare Workers backend that powers
 * desktop's `https://links.parachord.app/<id>` rich share pages — feature.fm /
 * linkfire-style landing pages with title, artwork, and per-service play
 * buttons. Same backend, same URL shape; the recipient gets a rich preview
 * (Open Graph / oEmbed) on Slack, Discord, iMessage, etc.
 *
 * The `/api/create` endpoint is open (CORS `*`, no auth) — see
 * `smart-links/functions/api/create.js` in the Parachord/parachord repo.
 */
interface SmartLinkApi {
    @POST("api/create")
    suspend fun create(@Body request: SmartLinkCreateRequest): SmartLinkCreateResponse
}

@Serializable
data class SmartLinkCreateRequest(
    val title: String,
    val artist: String? = null,
    val creator: String? = null,
    @SerialName("albumArt") val albumArt: String? = null,
    /** "track" | "album" | "playlist". Server enriches missing service URLs in background. */
    val type: String = "track",
    /** For tracks: per-service URL map. Required when type=track. */
    val urls: Map<String, String>? = null,
    /** For albums/playlists: tracklist. Required when type=album|playlist. */
    val tracks: List<SmartLinkTrack>? = null,
)

@Serializable
data class SmartLinkTrack(
    val title: String,
    val artist: String? = null,
    val duration: Long? = null,
    val trackNumber: Int? = null,
    val urls: Map<String, String> = emptyMap(),
    @SerialName("albumArt") val albumArt: String? = null,
)

@Serializable
data class SmartLinkCreateResponse(
    val id: String,
    val url: String,
)
