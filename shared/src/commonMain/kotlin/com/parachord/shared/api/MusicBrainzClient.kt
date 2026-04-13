package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MusicBrainz API v2 client.
 * Free, no auth required. Rate limited to 1 req/sec.
 * https://musicbrainz.org/doc/MusicBrainz_API
 */
class MusicBrainzClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://musicbrainz.org/ws/2"
    }

    suspend fun searchRecordings(query: String, limit: Int = 20): MbRecordingSearchResponse =
        httpClient.get("$BASE_URL/recording/") {
            parameter("query", query)
            parameter("limit", limit)
            parameter("fmt", "json")
        }.body()

    suspend fun searchReleases(query: String, limit: Int = 10): MbReleaseSearchResponse =
        httpClient.get("$BASE_URL/release/") {
            parameter("query", query)
            parameter("limit", limit)
            parameter("fmt", "json")
        }.body()

    suspend fun searchArtists(query: String, limit: Int = 10): MbArtistSearchResponse =
        httpClient.get("$BASE_URL/artist/") {
            parameter("query", query)
            parameter("limit", limit)
            parameter("fmt", "json")
        }.body()

    suspend fun getRelease(
        releaseId: String,
        inc: String = "recordings+artist-credits",
    ): MbReleaseDetail =
        httpClient.get("$BASE_URL/release/$releaseId") {
            parameter("inc", inc)
            parameter("fmt", "json")
        }.body()

    suspend fun browseReleaseGroups(
        artistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): MbReleaseGroupBrowseResponse =
        httpClient.get("$BASE_URL/release-group") {
            parameter("artist", artistId)
            parameter("limit", limit)
            parameter("offset", offset)
            parameter("fmt", "json")
        }.body()

    suspend fun getArtist(
        artistId: String,
        inc: String = "url-rels",
    ): MbArtistDetail =
        httpClient.get("$BASE_URL/artist/$artistId") {
            parameter("inc", inc)
            parameter("fmt", "json")
        }.body()
}

// ── Response Models ──────────────────────────────────────────────────

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
