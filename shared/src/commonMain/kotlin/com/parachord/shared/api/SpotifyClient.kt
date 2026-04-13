package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Spotify Web API client.
 * Base URL: https://api.spotify.com/
 * Auth: Bearer token in Authorization header.
 */
class SpotifyClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE = "https://api.spotify.com"
    }

    // ── Search + Lookup ──────────────────────────────────────────────

    suspend fun search(auth: String, query: String, type: String, limit: Int = 20, market: String = "from_token"): SpSearchResponse =
        httpClient.get("$BASE/v1/search") {
            header("Authorization", auth)
            parameter("q", query); parameter("type", type); parameter("limit", limit); parameter("market", market)
        }.body()

    suspend fun getTrack(auth: String, trackId: String, market: String = "from_token"): SpTrack =
        httpClient.get("$BASE/v1/tracks/$trackId") {
            header("Authorization", auth); parameter("market", market)
        }.body()

    suspend fun getArtist(auth: String, artistId: String): SpArtist =
        httpClient.get("$BASE/v1/artists/$artistId") { header("Authorization", auth) }.body()

    suspend fun getArtistTopTracks(auth: String, artistId: String, market: String = "US"): SpTopTracksResponse =
        httpClient.get("$BASE/v1/artists/$artistId/top-tracks") {
            header("Authorization", auth); parameter("market", market)
        }.body()

    suspend fun getArtistAlbums(auth: String, artistId: String, includeGroups: String = "album,single,compilation", limit: Int = 50): SpPaginated<SpAlbum> =
        httpClient.get("$BASE/v1/artists/$artistId/albums") {
            header("Authorization", auth); parameter("include_groups", includeGroups); parameter("limit", limit)
        }.body()

    suspend fun getAlbumTracks(auth: String, albumId: String, limit: Int = 50): SpPaginated<SpSimpleTrack> =
        httpClient.get("$BASE/v1/albums/$albumId/tracks") {
            header("Authorization", auth); parameter("limit", limit)
        }.body()

    // ── Playback Control (Spotify Connect) ───────────────────────────

    suspend fun getDevices(auth: String): SpDevicesResponse =
        httpClient.get("$BASE/v1/me/player/devices") { header("Authorization", auth) }.body()

    suspend fun transferPlayback(auth: String, body: SpTransferRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/player") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun startPlayback(auth: String, body: SpPlaybackRequest, deviceId: String? = null): HttpResponse =
        httpClient.put("$BASE/v1/me/player/play") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
            if (deviceId != null) parameter("device_id", deviceId)
        }

    suspend fun resumePlayback(auth: String): HttpResponse =
        httpClient.put("$BASE/v1/me/player/play") { header("Authorization", auth) }

    suspend fun pausePlayback(auth: String): HttpResponse =
        httpClient.put("$BASE/v1/me/player/pause") { header("Authorization", auth) }

    suspend fun seekPlayback(auth: String, positionMs: Long): HttpResponse =
        httpClient.put("$BASE/v1/me/player/seek") {
            header("Authorization", auth); parameter("position_ms", positionMs)
        }

    suspend fun getPlaybackState(auth: String): HttpResponse =
        httpClient.get("$BASE/v1/me/player") { header("Authorization", auth) }

    // ── Library (Sync Read) ──────────────────────────────────────────

    suspend fun getLikedTracks(auth: String, limit: Int = 50, offset: Int = 0, market: String = "from_token"): SpSavedTracksResponse =
        httpClient.get("$BASE/v1/me/tracks") {
            header("Authorization", auth); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }.body()

    suspend fun getSavedAlbums(auth: String, limit: Int = 50, offset: Int = 0, market: String = "from_token"): SpSavedAlbumsResponse =
        httpClient.get("$BASE/v1/me/albums") {
            header("Authorization", auth); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }.body()

    suspend fun getFollowedArtists(auth: String, limit: Int = 50, after: String? = null): SpFollowedArtistsResponse =
        httpClient.get("$BASE/v1/me/following") {
            header("Authorization", auth); parameter("type", "artist"); parameter("limit", limit)
            if (after != null) parameter("after", after)
        }.body()

    suspend fun getUserPlaylists(auth: String, limit: Int = 50, offset: Int = 0): SpPaginatedPlaylists =
        httpClient.get("$BASE/v1/me/playlists") {
            header("Authorization", auth); parameter("limit", limit); parameter("offset", offset)
        }.body()

    suspend fun getPlaylistTracks(auth: String, playlistId: String, limit: Int = 100, offset: Int = 0, market: String = "from_token"): SpPlaylistTracksResponse =
        httpClient.get("$BASE/v1/playlists/$playlistId/tracks") {
            header("Authorization", auth); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }.body()

    suspend fun getPlaylist(auth: String, playlistId: String, fields: String? = null): SpPlaylistFull =
        httpClient.get("$BASE/v1/playlists/$playlistId") {
            header("Authorization", auth); if (fields != null) parameter("fields", fields)
        }.body()

    suspend fun getCurrentUser(auth: String): SpUser =
        httpClient.get("$BASE/v1/me") { header("Authorization", auth) }.body()

    // ── Library Write ────────────────────────────────────────────────

    suspend fun saveTracks(auth: String, body: SpIdsRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/tracks") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun removeTracks(auth: String, body: SpIdsRequest): HttpResponse =
        httpClient.delete("$BASE/v1/me/tracks") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun saveAlbums(auth: String, body: SpIdsRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/albums") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun removeAlbums(auth: String, body: SpIdsRequest): HttpResponse =
        httpClient.delete("$BASE/v1/me/albums") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun followArtists(auth: String, body: SpIdsRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/following") {
            header("Authorization", auth); parameter("type", "artist")
            contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun unfollowArtists(auth: String, body: SpIdsRequest): HttpResponse =
        httpClient.delete("$BASE/v1/me/following") {
            header("Authorization", auth); parameter("type", "artist")
            contentType(ContentType.Application.Json); setBody(body)
        }

    // ── Playlist Write ───────────────────────────────────────────────

    suspend fun createPlaylist(auth: String, userId: String, body: SpCreatePlaylistRequest): SpPlaylistFull =
        httpClient.post("$BASE/v1/users/$userId/playlists") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }.body()

    suspend fun replacePlaylistTracks(auth: String, playlistId: String, body: SpUrisRequest): HttpResponse =
        httpClient.put("$BASE/v1/playlists/$playlistId/tracks") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun addPlaylistTracks(auth: String, playlistId: String, body: SpUrisRequest): HttpResponse =
        httpClient.post("$BASE/v1/playlists/$playlistId/tracks") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun updatePlaylistDetails(auth: String, playlistId: String, body: SpUpdatePlaylistRequest): HttpResponse =
        httpClient.put("$BASE/v1/playlists/$playlistId") {
            header("Authorization", auth); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun unfollowPlaylist(auth: String, playlistId: String): HttpResponse =
        httpClient.delete("$BASE/v1/playlists/$playlistId/followers") { header("Authorization", auth) }
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class SpTopTracksResponse(val tracks: List<SpTrack> = emptyList())

@Serializable
data class SpSearchResponse(
    val tracks: SpPaginated<SpTrack>? = null,
    val albums: SpPaginated<SpAlbum>? = null,
    val artists: SpPaginated<SpArtist>? = null,
)

@Serializable
data class SpPaginated<T>(val items: List<T> = emptyList(), val total: Int = 0)

@Serializable
data class SpTrack(
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpArtistRef> = emptyList(),
    val album: SpAlbumRef? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

@Serializable
data class SpArtistRef(val id: String? = null, val name: String? = null)

@Serializable
data class SpAlbumRef(
    val id: String? = null, val name: String? = null, val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
)

@Serializable
data class SpAlbum(
    val id: String? = null, val name: String? = null, val artists: List<SpArtistRef> = emptyList(),
    val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
    @SerialName("album_type") val albumType: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class SpSimpleTrack(
    val id: String? = null, val name: String? = null, val artists: List<SpArtistRef> = emptyList(),
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

@Serializable
data class SpArtist(val id: String? = null, val name: String? = null, val genres: List<String> = emptyList(), val images: List<SpImage>? = null)

@Serializable
data class SpImage(val url: String? = null, val height: Int? = null, val width: Int? = null)

fun List<SpImage>?.bestImageUrl(): String? =
    this?.filter { it.url != null }?.sortedBy { it.width ?: 0 }?.firstOrNull { (it.width ?: 0) >= 300 }?.url
        ?: this?.firstOrNull { it.url != null }?.url

@Serializable data class SpDevicesResponse(val devices: List<SpDevice> = emptyList())
@Serializable data class SpDevice(val id: String, val name: String, @SerialName("is_active") val isActive: Boolean = false, @SerialName("is_restricted") val isRestricted: Boolean = false, val type: String = "", @SerialName("volume_percent") val volumePercent: Int? = null)
@Serializable data class SpTransferRequest(@SerialName("device_ids") val deviceIds: List<String>, val play: Boolean = false)
@Serializable data class SpPlaybackRequest(val uris: List<String>? = null, @SerialName("context_uri") val contextUri: String? = null)
@Serializable data class SpPlaybackState(@SerialName("is_playing") val isPlaying: Boolean = false, @SerialName("progress_ms") val progressMs: Long? = null, val item: SpTrack? = null, val device: SpDevice? = null)
@Serializable data class SpSavedTrack(@SerialName("added_at") val addedAt: String? = null, val track: SpTrack? = null)
@Serializable data class SpSavedTracksResponse(val items: List<SpSavedTrack> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpSavedAlbum(@SerialName("added_at") val addedAt: String? = null, val album: SpAlbum? = null)
@Serializable data class SpSavedAlbumsResponse(val items: List<SpSavedAlbum> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpFollowedArtistsResponse(val artists: SpCursorPaginated)
@Serializable data class SpCursorPaginated(val items: List<SpArtist> = emptyList(), val total: Int = 0, val cursors: SpCursors? = null, val next: String? = null)
@Serializable data class SpCursors(val after: String? = null)
@Serializable data class SpPlaylistSimple(val id: String? = null, val name: String? = null, val description: String? = null, val images: List<SpImage>? = null, val owner: SpUser? = null, @SerialName("snapshot_id") val snapshotId: String? = null, val tracks: SpPlaylistTracksRef? = null)
@Serializable data class SpPlaylistTracksRef(val total: Int = 0)
@Serializable data class SpPaginatedPlaylists(val items: List<SpPlaylistSimple> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpPlaylistTrackItem(@SerialName("added_at") val addedAt: String? = null, val track: SpTrack? = null)
@Serializable data class SpPlaylistTracksResponse(val items: List<SpPlaylistTrackItem> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 100, val next: String? = null)
@Serializable data class SpPlaylistFull(val id: String? = null, val name: String? = null, val description: String? = null, val images: List<SpImage>? = null, val owner: SpUser? = null, @SerialName("snapshot_id") val snapshotId: String? = null, val tracks: SpPlaylistTracksResponse? = null)
@Serializable data class SpUser(val id: String, @SerialName("display_name") val displayName: String? = null, val country: String? = null)
@Serializable data class SpIdsRequest(val ids: List<String>)
@Serializable data class SpUrisRequest(val uris: List<String>)
@Serializable data class SpCreatePlaylistRequest(val name: String, val description: String? = null, val public: Boolean = false)
@Serializable data class SpUpdatePlaylistRequest(val name: String? = null, val description: String? = null)
@Serializable data class SpSnapshotResponse(@SerialName("snapshot_id") val snapshotId: String)
