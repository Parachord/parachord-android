package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Last.fm API v2.
 * Requires api_key parameter on every request.
 * https://www.last.fm/api
 */
interface LastFmApi {

    @GET(".")
    suspend fun searchTracks(
        @Query("method") method: String = "track.search",
        @Query("track") track: String,
        @Query("api_key") apiKey: String,
        @Query("limit") limit: Int = 20,
        @Query("format") format: String = "json",
    ): LfmTrackSearchResponse

    @GET(".")
    suspend fun searchAlbums(
        @Query("method") method: String = "album.search",
        @Query("album") album: String,
        @Query("api_key") apiKey: String,
        @Query("limit") limit: Int = 10,
        @Query("format") format: String = "json",
    ): LfmAlbumSearchResponse

    @GET(".")
    suspend fun searchArtists(
        @Query("method") method: String = "artist.search",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("limit") limit: Int = 10,
        @Query("format") format: String = "json",
    ): LfmArtistSearchResponse

    @GET(".")
    suspend fun getArtistInfo(
        @Query("method") method: String = "artist.getinfo",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmArtistInfoResponse
}

// --- Response models ---

@Serializable
data class LfmTrackSearchResponse(
    val results: LfmTrackResults? = null,
)

@Serializable
data class LfmTrackResults(
    val trackmatches: LfmTrackMatches? = null,
)

@Serializable
data class LfmTrackMatches(
    val track: List<LfmTrack> = emptyList(),
)

@Serializable
data class LfmTrack(
    val name: String,
    val artist: String,
    val url: String? = null,
    val listeners: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmAlbumSearchResponse(
    val results: LfmAlbumResults? = null,
)

@Serializable
data class LfmAlbumResults(
    val albummatches: LfmAlbumMatches? = null,
)

@Serializable
data class LfmAlbumMatches(
    val album: List<LfmAlbum> = emptyList(),
)

@Serializable
data class LfmAlbum(
    val name: String,
    val artist: String,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmArtistSearchResponse(
    val results: LfmArtistResults? = null,
)

@Serializable
data class LfmArtistResults(
    val artistmatches: LfmArtistMatches? = null,
)

@Serializable
data class LfmArtistMatches(
    val artist: List<LfmArtistSummary> = emptyList(),
)

@Serializable
data class LfmArtistSummary(
    val name: String,
    val listeners: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
)

@Serializable
data class LfmArtistInfoResponse(
    val artist: LfmArtistDetail? = null,
)

@Serializable
data class LfmArtistDetail(
    val name: String,
    val mbid: String? = null,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
    val bio: LfmBio? = null,
    val tags: LfmTags? = null,
    val similar: LfmSimilar? = null,
)

@Serializable
data class LfmBio(
    val summary: String? = null,
    val content: String? = null,
)

@Serializable
data class LfmTags(
    val tag: List<LfmTag> = emptyList(),
)

@Serializable
data class LfmTag(
    val name: String,
)

@Serializable
data class LfmSimilar(
    val artist: List<LfmSimilarArtist> = emptyList(),
)

@Serializable
data class LfmSimilarArtist(
    val name: String,
    val url: String? = null,
)

@Serializable
data class LfmImage(
    @SerialName("#text") val url: String = "",
    val size: String = "",
) {
    val isUsable: Boolean get() = url.isNotBlank() && !url.contains("2a96cbd8b46e442fc41c2b86b821562f")
}

/** Get the best available image URL from a list of Last.fm images. */
fun List<LfmImage>.bestImageUrl(): String? =
    lastOrNull { it.isUsable }?.url
