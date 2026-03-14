package com.parachord.android.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    @GET(".")
    suspend fun getSimilarArtists(
        @Query("method") method: String = "artist.getsimilar",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("limit") limit: Int = 20,
        @Query("format") format: String = "json",
    ): LfmSimilarArtistsResponse

    @GET(".")
    suspend fun getArtistTopTracks(
        @Query("method") method: String = "artist.gettoptracks",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("limit") limit: Int = 10,
        @Query("format") format: String = "json",
    ): LfmTopTracksResponse

    @GET(".")
    suspend fun getArtistTopAlbums(
        @Query("method") method: String = "artist.gettopalbums",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("limit") limit: Int = 50,
        @Query("format") format: String = "json",
    ): LfmTopAlbumsResponse

    @GET(".")
    suspend fun getAlbumInfo(
        @Query("method") method: String = "album.getinfo",
        @Query("album") album: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmAlbumInfoResponse

    @GET(".")
    suspend fun getUserInfo(
        @Query("method") method: String = "user.getinfo",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmUserInfoResponse

    // --- User-based endpoints ---

    @GET(".")
    suspend fun getUserTopTracks(
        @Query("method") method: String = "user.gettoptracks",
        @Query("user") user: String,
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 50,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmUserTopTracksResponse

    @GET(".")
    suspend fun getUserTopAlbums(
        @Query("method") method: String = "user.gettopalbums",
        @Query("user") user: String,
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 50,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmUserTopAlbumsResponse

    @GET(".")
    suspend fun getUserTopArtists(
        @Query("method") method: String = "user.gettopartists",
        @Query("user") user: String,
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 50,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmUserTopArtistsResponse

    @GET(".")
    suspend fun getUserRecentTracks(
        @Query("method") method: String = "user.getrecenttracks",
        @Query("user") user: String,
        @Query("limit") limit: Int = 50,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
    ): LfmUserRecentTracksResponse
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
    val image: List<LfmImage> = emptyList(),
)

@Serializable
data class LfmSimilarArtistsResponse(
    val similarartists: LfmSimilar? = null,
)

@Serializable
data class LfmAlbumInfoResponse(
    val album: LfmAlbumDetail? = null,
)

@Serializable
data class LfmAlbumDetail(
    val name: String,
    val artist: String,
    val image: List<LfmImage> = emptyList(),
    val tracks: LfmAlbumTracks? = null,
    val mbid: String? = null,
    val wiki: LfmBio? = null,
)

@Serializable
data class LfmAlbumTracks(
    val track: LfmAlbumTrackList = LfmAlbumTrackList(),
)

@Serializable(with = LfmAlbumTrackListSerializer::class)
data class LfmAlbumTrackList(
    val items: List<LfmAlbumTrack> = emptyList(),
)

@Serializable
data class LfmAlbumTrack(
    val name: String,
    val duration: String? = null,
    val artist: LfmAlbumTrackArtist? = null,
    @SerialName("@attr") val attr: LfmTrackAttr? = null,
)

@Serializable
data class LfmAlbumTrackArtist(
    val name: String,
)

@Serializable
data class LfmTrackAttr(
    val rank: Int? = null,
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

/**
 * Last.fm returns `track` as either a single object (1 track) or an array (multiple tracks).
 * This serializer handles both cases.
 */
// --- Artist top tracks / top albums ---

@Serializable
data class LfmTopTracksResponse(
    val toptracks: LfmTopTracks? = null,
)

@Serializable
data class LfmTopTracks(
    val track: List<LfmTopTrack> = emptyList(),
)

@Serializable
data class LfmTopTrack(
    val name: String,
    val duration: String? = null,
    val listeners: String? = null,
    val playcount: String? = null,
    val artist: LfmTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmTopTrackArtist(
    val name: String,
    val mbid: String? = null,
)

@Serializable
data class LfmTopAlbumsResponse(
    val topalbums: LfmTopAlbums? = null,
)

@Serializable
data class LfmTopAlbums(
    val album: List<LfmTopAlbum> = emptyList(),
)

@Serializable
data class LfmTopAlbum(
    val name: String,
    val playcount: String? = null,
    val artist: LfmTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

// --- User top tracks ---

@Serializable
data class LfmUserTopTracksResponse(
    val toptracks: LfmUserTopTracks? = null,
)

@Serializable
data class LfmUserTopTracks(
    val track: List<LfmUserTopTrack> = emptyList(),
)

@Serializable
data class LfmUserTopTrack(
    val name: String,
    val playcount: String? = null,
    val artist: LfmUserTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
    val url: String? = null,
    @SerialName("@attr") val attr: LfmUserRankAttr? = null,
)

@Serializable
data class LfmUserTopTrackArtist(
    val name: String,
    val mbid: String? = null,
)

// --- User top albums ---

@Serializable
data class LfmUserTopAlbumsResponse(
    val topalbums: LfmUserTopAlbums? = null,
)

@Serializable
data class LfmUserTopAlbums(
    val album: List<LfmUserTopAlbum> = emptyList(),
)

@Serializable
data class LfmUserTopAlbum(
    val name: String,
    val playcount: String? = null,
    val artist: LfmUserTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
    val url: String? = null,
    @SerialName("@attr") val attr: LfmUserRankAttr? = null,
)

// --- User top artists ---

@Serializable
data class LfmUserTopArtistsResponse(
    val topartists: LfmUserTopArtists? = null,
)

@Serializable
data class LfmUserTopArtists(
    val artist: List<LfmUserTopArtist> = emptyList(),
)

@Serializable
data class LfmUserTopArtist(
    val name: String,
    val playcount: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
    val url: String? = null,
    @SerialName("@attr") val attr: LfmUserRankAttr? = null,
)

// --- User recent tracks ---

@Serializable
data class LfmUserRecentTracksResponse(
    val recenttracks: LfmUserRecentTracks? = null,
)

@Serializable
data class LfmUserRecentTracks(
    val track: List<LfmUserRecentTrack> = emptyList(),
)

@Serializable
data class LfmUserRecentTrack(
    val name: String,
    val artist: LfmUserRecentTrackArtist? = null,
    val album: LfmUserRecentTrackAlbum? = null,
    val image: List<LfmImage> = emptyList(),
    val url: String? = null,
    val mbid: String? = null,
    val date: LfmUserTrackDate? = null,
    @SerialName("@attr") val attr: LfmUserNowPlayingAttr? = null,
)

@Serializable
data class LfmUserRecentTrackArtist(
    @SerialName("#text") val name: String,
    val mbid: String? = null,
)

@Serializable
data class LfmUserRecentTrackAlbum(
    @SerialName("#text") val name: String,
    val mbid: String? = null,
)

@Serializable
data class LfmUserTrackDate(
    val uts: String? = null,
    @SerialName("#text") val text: String? = null,
)

@Serializable
data class LfmUserNowPlayingAttr(
    val nowplaying: String? = null,
)

// --- Shared user attr ---

@Serializable
data class LfmUserRankAttr(
    val rank: String? = null,
)

// --- User info ---

@Serializable
data class LfmUserInfoResponse(
    val user: LfmUserInfo? = null,
)

@Serializable
data class LfmUserInfo(
    val name: String,
    val realname: String? = null,
    val image: List<LfmImage> = emptyList(),
    val url: String? = null,
    val playcount: String? = null,
)

object LfmAlbumTrackListSerializer : KSerializer<LfmAlbumTrackList> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LfmAlbumTrackList")

    override fun serialize(encoder: Encoder, value: LfmAlbumTrackList) {
        encoder.encodeSerializableValue(
            ListSerializer(LfmAlbumTrack.serializer()),
            value.items,
        )
    }

    override fun deserialize(decoder: Decoder): LfmAlbumTrackList {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> {
                val items = element.map { jsonDecoder.json.decodeFromJsonElement(LfmAlbumTrack.serializer(), it) }
                LfmAlbumTrackList(items)
            }
            is JsonObject -> {
                val item = jsonDecoder.json.decodeFromJsonElement(LfmAlbumTrack.serializer(), element)
                LfmAlbumTrackList(listOf(item))
            }
            else -> LfmAlbumTrackList()
        }
    }
}
