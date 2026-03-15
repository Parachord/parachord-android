package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search/Lookup API — no authentication required.
 * Used for looking up Apple Music content by ID (from shared URLs).
 *
 * Desktop equivalent: apple-music.axe → lookupUrl(), lookupAlbum()
 */
interface AppleMusicApi {

    @GET("lookup")
    suspend fun lookup(
        @Query("id") id: String,
        @Query("entity") entity: String? = null,
    ): AppleMusicLookupResponse

    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25,
    ): AppleMusicSearchResponse
}

@Serializable
data class AppleMusicLookupResponse(
    val resultCount: Int = 0,
    val results: List<AppleMusicItem> = emptyList(),
)

@Serializable
data class AppleMusicSearchResponse(
    val resultCount: Int = 0,
    val results: List<AppleMusicItem> = emptyList(),
)

@Serializable
data class AppleMusicItem(
    val wrapperType: String? = null,
    val kind: String? = null,
    val trackId: Long? = null,
    val collectionId: Long? = null,
    val artistId: Long? = null,
    val trackName: String? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val collectionType: String? = null,
)
