package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
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
        @Query("market") market: String = "from_token",
    ): SpSearchResponse

    @GET("v1/tracks/{id}")
    suspend fun getTrack(
        @Header("Authorization") auth: String,
        @Path("id") trackId: String,
        @Query("market") market: String = "from_token",
    ): SpTrack

    @GET("v1/artists/{id}")
    suspend fun getArtist(
        @Header("Authorization") auth: String,
        @Path("id") artistId: String,
    ): SpArtist

    @GET("v1/artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Header("Authorization") auth: String,
        @Path("id") artistId: String,
        @Query("market") market: String = "US",
    ): SpTopTracksResponse

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

    // --- Library (Sync) ---

    @GET("v1/me/tracks")
    suspend fun getLikedTracks(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): SpSavedTracksResponse

    @GET("v1/me/albums")
    suspend fun getSavedAlbums(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): SpSavedAlbumsResponse

    @GET("v1/me/following")
    suspend fun getFollowedArtists(
        @Header("Authorization") auth: String,
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int = 50,
        @Query("after") after: String? = null,
    ): SpFollowedArtistsResponse

    @GET("v1/me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): SpPaginatedPlaylists

    @GET("v1/playlists/{id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): SpPlaylistTracksResponse

    @GET("v1/playlists/{id}")
    suspend fun getPlaylist(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Query("fields") fields: String? = null,
    ): SpPlaylistFull

    @GET("v1/me")
    suspend fun getCurrentUser(
        @Header("Authorization") auth: String,
    ): SpUser

    // --- Library Write (Sync push-back) ---

    @PUT("v1/me/tracks")
    suspend fun saveTracks(
        @Header("Authorization") auth: String,
        @Body body: SpIdsRequest,
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "v1/me/tracks", hasBody = true)
    suspend fun removeTracks(
        @Header("Authorization") auth: String,
        @Body body: SpIdsRequest,
    ): Response<Unit>

    @PUT("v1/me/albums")
    suspend fun saveAlbums(
        @Header("Authorization") auth: String,
        @Body body: SpIdsRequest,
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "v1/me/albums", hasBody = true)
    suspend fun removeAlbums(
        @Header("Authorization") auth: String,
        @Body body: SpIdsRequest,
    ): Response<Unit>

    @PUT("v1/me/following")
    suspend fun followArtists(
        @Header("Authorization") auth: String,
        @Query("type") type: String = "artist",
        @Body body: SpIdsRequest,
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "v1/me/following", hasBody = true)
    suspend fun unfollowArtists(
        @Header("Authorization") auth: String,
        @Query("type") type: String = "artist",
        @Body body: SpIdsRequest,
    ): Response<Unit>

    // --- Playlist Write ---

    @POST("v1/users/{userId}/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") auth: String,
        @Path("userId") userId: String,
        @Body body: SpCreatePlaylistRequest,
    ): SpPlaylistFull

    @PUT("v1/playlists/{id}/tracks")
    suspend fun replacePlaylistTracks(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: SpUrisRequest,
    ): Response<SpSnapshotResponse>

    @POST("v1/playlists/{id}/tracks")
    suspend fun addPlaylistTracks(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: SpUrisRequest,
    ): Response<SpSnapshotResponse>

    @PUT("v1/playlists/{id}")
    suspend fun updatePlaylistDetails(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
        @Body body: SpUpdatePlaylistRequest,
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "v1/playlists/{id}/followers")
    suspend fun unfollowPlaylist(
        @Header("Authorization") auth: String,
        @Path("id") playlistId: String,
    ): Response<Unit>
}

// --- Response models ---

@Serializable
data class SpTopTracksResponse(
    val tracks: List<SpTrack> = emptyList(),
)

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
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpArtistRef> = emptyList(),
    val album: SpAlbumRef? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    /** Whether the track is playable in the user's market. Only present when market param is set. */
    @SerialName("is_playable") val isPlayable: Boolean? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class SpArtistRef(
    val id: String? = null,
    val name: String,
)

@Serializable
data class SpAlbumRef(
    val id: String? = null,
    val name: String? = null,
    val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
)

@Serializable
data class SpAlbum(
    val id: String,
    val name: String,
    val artists: List<SpArtistRef> = emptyList(),
    val images: List<SpImage>? = null,
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
    val images: List<SpImage>? = null,
)

@Serializable
data class SpImage(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
)

/** Get the best image URL (prefer medium ~300px, fall back to first available). */
fun List<SpImage>?.bestImageUrl(): String? =
    this?.sortedBy { it.width ?: 0 }?.firstOrNull { (it.width ?: 0) >= 300 }?.url
        ?: this?.firstOrNull()?.url

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

// --- Library Sync response models ---

@Serializable
data class SpSavedTrack(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpTrack? = null,
)

@Serializable
data class SpSavedTracksResponse(
    val items: List<SpSavedTrack> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val next: String? = null,
)

@Serializable
data class SpSavedAlbum(
    @SerialName("added_at") val addedAt: String? = null,
    val album: SpAlbum,
)

@Serializable
data class SpSavedAlbumsResponse(
    val items: List<SpSavedAlbum> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val next: String? = null,
)

@Serializable
data class SpFollowedArtistsResponse(
    val artists: SpCursorPaginated,
)

@Serializable
data class SpCursorPaginated(
    val items: List<SpArtist> = emptyList(),
    val total: Int = 0,
    val cursors: SpCursors? = null,
    val next: String? = null,
)

@Serializable
data class SpCursors(
    val after: String? = null,
)

@Serializable
data class SpPlaylistSimple(
    val id: String,
    val name: String,
    val description: String? = null,
    val images: List<SpImage>? = null,
    val owner: SpUser? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpPlaylistTracksRef? = null,
)

@Serializable
data class SpPlaylistTracksRef(
    val total: Int = 0,
)

@Serializable
data class SpPaginatedPlaylists(
    val items: List<SpPlaylistSimple> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val next: String? = null,
)

@Serializable
data class SpPlaylistTrackItem(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpTrack? = null,
)

@Serializable
data class SpPlaylistTracksResponse(
    val items: List<SpPlaylistTrackItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 100,
    val next: String? = null,
)

@Serializable
data class SpPlaylistFull(
    val id: String,
    val name: String,
    val description: String? = null,
    val images: List<SpImage>? = null,
    val owner: SpUser? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpPlaylistTracksResponse? = null,
)

@Serializable
data class SpUser(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

// --- Library Write request models ---

@Serializable
data class SpIdsRequest(
    val ids: List<String>,
)

@Serializable
data class SpUrisRequest(
    val uris: List<String>,
)

@Serializable
data class SpCreatePlaylistRequest(
    val name: String,
    val description: String? = null,
    val public: Boolean = false,
)

@Serializable
data class SpUpdatePlaylistRequest(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class SpSnapshotResponse(
    @SerialName("snapshot_id") val snapshotId: String,
)
