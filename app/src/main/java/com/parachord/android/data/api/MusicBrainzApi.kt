package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * MusicBrainz API v2.
 * Free, no auth required. Rate limited to 1 req/sec.
 * https://musicbrainz.org/doc/MusicBrainz_API
 */
interface MusicBrainzApi {

    @GET("recording/")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20,
        @Query("fmt") fmt: String = "json",
    ): MbRecordingSearchResponse

    @GET("release/")
    suspend fun searchReleases(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("fmt") fmt: String = "json",
    ): MbReleaseSearchResponse

    @GET("artist/")
    suspend fun searchArtists(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("fmt") fmt: String = "json",
    ): MbArtistSearchResponse
}

// --- Response models ---

@Serializable
data class MbRecordingSearchResponse(
    val recordings: List<MbRecording> = emptyList(),
)

@Serializable
data class MbRecording(
    val id: String,
    val title: String,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbReleaseRef> = emptyList(),
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val albumTitle: String? get() = releases.firstOrNull()?.title
}

@Serializable
data class MbArtistCredit(
    val name: String,
    val artist: MbArtistRef? = null,
)

@Serializable
data class MbArtistRef(
    val id: String,
    val name: String,
)

@Serializable
data class MbReleaseRef(
    val id: String,
    val title: String,
)

@Serializable
data class MbReleaseSearchResponse(
    val releases: List<MbRelease> = emptyList(),
)

@Serializable
data class MbRelease(
    val id: String,
    val title: String,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val date: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

@Serializable
data class MbArtistSearchResponse(
    val artists: List<MbArtist> = emptyList(),
)

@Serializable
data class MbArtist(
    val id: String,
    val name: String,
    val disambiguation: String? = null,
    val tags: List<MbTag> = emptyList(),
)

@Serializable
data class MbTag(
    val name: String,
    val count: Int = 0,
)
