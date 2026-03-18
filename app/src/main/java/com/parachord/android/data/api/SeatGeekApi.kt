package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * SeatGeek Platform API v2.
 * Docs: https://platform.seatgeek.com/
 */
interface SeatGeekApi {

    @GET("events")
    suspend fun searchEvents(
        @Query("q") query: String,
        @Query("type") type: String = "concert",
        @Query("client_id") clientId: String,
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null,
        @Query("range") range: String? = null, // e.g. "50mi"
        @Query("per_page") perPage: Int = 20,
        @Query("sort") sort: String = "datetime_local.asc",
        @Query("datetime_local.gte") datetimeGte: String? = null,
    ): SgSearchResponse

    @GET("events")
    suspend fun getLocalEvents(
        @Query("type") type: String = "concert",
        @Query("client_id") clientId: String,
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("range") range: String = "50mi",
        @Query("per_page") perPage: Int = 50,
        @Query("sort") sort: String = "datetime_local.asc",
        @Query("datetime_local.gte") datetimeGte: String? = null,
    ): SgSearchResponse
}

// ── Response Models ──────────────────────────────────────────

@Serializable
data class SgSearchResponse(
    val events: List<SgEvent> = emptyList(),
    val meta: SgMeta? = null,
)

@Serializable
data class SgMeta(
    val total: Int = 0,
    val took: Int = 0,
    val page: Int = 0,
    @SerialName("per_page") val perPage: Int = 0,
)

@Serializable
data class SgEvent(
    val id: Long,
    val title: String,
    val url: String? = null,
    @SerialName("datetime_local") val datetimeLocal: String? = null,
    @SerialName("datetime_utc") val datetimeUtc: String? = null,
    val venue: SgVenue? = null,
    val performers: List<SgPerformer> = emptyList(),
    val type: String? = null,
    @SerialName("short_title") val shortTitle: String? = null,
)

@Serializable
data class SgVenue(
    val id: Long? = null,
    val name: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    @SerialName("display_location") val displayLocation: String? = null,
    val location: SgLocation? = null,
)

@Serializable
data class SgLocation(
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class SgPerformer(
    val id: Long? = null,
    val name: String? = null,
    val image: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val type: String? = null,
)
