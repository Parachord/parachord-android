package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Spotify Web API — search + playback control endpoints.
 * Requires Bearer token in Authorization header.
 * https://developer.spotify.com/documentation/web-api
 */
interface SpotifyApi {

    // --- Search ---

    @GET("v1/search")
    suspend fun search(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("type") type: String,
        @Query("limit") limit: Int = 20,
    ): SpSearchResponse

    @GET("v1/artists/{id}")
    suspend fun getArtist(
        @Header("Authorization") auth: String,
        @Path("id") artistId: String,
    ): SpArtist

    @GET("v1/artists/{id}/albums")
    suspend fun getArtistAlbums(
        @Header("Authorization") auth: String,
        @Path("id") artistId: String,
        @Query("include_groups") includeGroups: String = "album,single,compilation",
        @Query("limit") limit: Int = 50,
    ): SpPaginated<SpAlbum>

    @GET("v1/albums/{id}/tracks")
    suspend fun getAlbumTracks(
        @Header("Authorization") auth: String,
        @Path("id") albumId: String,
        @Query("limit") limit: Int = 50,
    ): SpPaginated<SpSimpleTrack>

    // --- Playback Control (Spotify Connect) ---

    @GET("v1/me/player/devices")
    suspend fun getDevices(
        @Header("Authorization") auth: String,
    ): SpDevicesResponse

    @PUT("v1/me/player")
    suspend fun transferPlayback(
        @Header("Authorization") auth: String,
        @Body body: SpTransferRequest,
    ): Response<Unit>

    @PUT("v1/me/player/play")
    suspend fun startPlayback(
        @Header("Authorization") auth: String,
        @Body body: SpPlaybackRequest,
        @Query("device_id") deviceId: String? = null,
    ): Response<Unit>

    @PUT("v1/me/player/play")
    suspend fun resumePlayback(
        @Header("Authorization") auth: String,
    ): Response<Unit>

    @PUT("v1/me/player/pause")
    suspend fun pausePlayback(
        @Header("Authorization") auth: String,
    ): Response<Unit>

    @PUT("v1/me/player/seek")
    suspend fun seekPlayback(
        @Header("Authorization") auth: String,
        @Query("position_ms") positionMs: Long,
    ): Response<Unit>

    @GET("v1/me/player")
    suspend fun getPlaybackState(
        @Header("Authorization") auth: String,
    ): Response<SpPlaybackState>
}

// --- Response models ---

@Serializable
data class SpSearchResponse(
    val tracks: SpPaginated<SpTrack>? = null,
    val albums: SpPaginated<SpAlbum>? = null,
    val artists: SpPaginated<SpArtist>? = null,
)

@Serializable
data class SpPaginated<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SpTrack(
    val id: String,
    val name: String,
    val artists: List<SpArtistRef> = emptyList(),
    val album: SpAlbumRef? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class SpArtistRef(
    val id: String,
    val name: String,
)

@Serializable
data class SpAlbumRef(
    val id: String,
    val name: String,
    val images: List<SpImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
)

@Serializable
data class SpAlbum(
    val id: String,
    val name: String,
    val artists: List<SpArtistRef> = emptyList(),
    val images: List<SpImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
    @SerialName("album_type") val albumType: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class SpSimpleTrack(
    val id: String,
    val name: String,
    val artists: List<SpArtistRef> = emptyList(),
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class SpArtist(
    val id: String,
    val name: String,
    val genres: List<String> = emptyList(),
    val images: List<SpImage> = emptyList(),
)

@Serializable
data class SpImage(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
)

/** Get the best image URL (prefer medium ~300px, fall back to first available). */
fun List<SpImage>.bestImageUrl(): String? =
    sortedBy { it.width ?: 0 }.firstOrNull { (it.width ?: 0) >= 300 }?.url
        ?: firstOrNull()?.url

// --- Playback Control models ---

@Serializable
data class SpDevicesResponse(
    val devices: List<SpDevice> = emptyList(),
)

@Serializable
data class SpDevice(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    val type: String = "",
)

@Serializable
data class SpTransferRequest(
    @SerialName("device_ids") val deviceIds: List<String>,
    val play: Boolean = true,
)

@Serializable
data class SpPlaybackRequest(
    val uris: List<String>? = null,
    @SerialName("context_uri") val contextUri: String? = null,
)

@Serializable
data class SpPlaybackState(
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("progress_ms") val progressMs: Long? = null,
    val item: SpTrack? = null,
    val device: SpDevice? = null,
)
