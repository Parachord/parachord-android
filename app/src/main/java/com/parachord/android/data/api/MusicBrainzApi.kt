package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
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

    /** Look up a release by MBID with recordings included for tracklist. */
    @GET("release/{id}")
    suspend fun getRelease(
        @Path("id") releaseId: String,
        @Query("inc") inc: String = "recordings+artist-credits",
        @Query("fmt") fmt: String = "json",
    ): MbReleaseDetail

    /** Browse release-groups for an artist by MBID. Returns the actual discography. */
    @GET("release-group")
    suspend fun browseReleaseGroups(
        @Query("artist") artistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("fmt") fmt: String = "json",
    ): MbReleaseGroupBrowseResponse

    /** Look up an artist by MBID with URL relations (for Wikidata/Discogs links). */
    @GET("artist/{id}")
    suspend fun getArtist(
        @Path("id") artistId: String,
        @Query("inc") inc: String = "url-rels",
        @Query("fmt") fmt: String = "json",
    ): MbArtistDetail
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
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

@Serializable
data class MbReleaseGroup(
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
)

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

// --- Release detail (lookup with inc=recordings) ---

@Serializable
data class MbReleaseDetail(
    val id: String,
    val title: String,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val date: String? = null,
    val media: List<MbMedia> = emptyList(),
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

@Serializable
data class MbMedia(
    val position: Int? = null,
    val format: String? = null,
    val tracks: List<MbTrack> = emptyList(),
)

@Serializable
data class MbTrack(
    val id: String,
    val number: String? = null,
    val title: String,
    val length: Long? = null,
    val position: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val recording: MbTrackRecording? = null,
) {
    val artistName: String
        get() = artistCredit.ifEmpty { recording?.artistCredit ?: emptyList() }
            .joinToString(", ") { it.name }
}

@Serializable
data class MbTrackRecording(
    val id: String,
    val title: String,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

// --- Release-group browse (artist discography) ---

@Serializable
data class MbReleaseGroupBrowseResponse(
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroupEntry> = emptyList(),
    @SerialName("release-group-count") val releaseGroupCount: Int = 0,
    @SerialName("release-group-offset") val releaseGroupOffset: Int = 0,
)

@Serializable
data class MbReleaseGroupEntry(
    val id: String,
    val title: String,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = firstReleaseDate?.take(4)?.toIntOrNull()
}

// --- Artist detail (lookup with inc=url-rels) ---

@Serializable
data class MbArtistDetail(
    val id: String,
    val name: String,
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
data class MbRelation(
    val type: String = "",
    val url: MbRelationUrl? = null,
)

@Serializable
data class MbRelationUrl(
    val resource: String = "",
)
