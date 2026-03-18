package com.parachord.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Ticketmaster Discovery API v2.
 * Docs: https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/
 */
interface TicketmasterApi {

    @GET("discovery/v2/events.json")
    suspend fun searchEvents(
        @Query("keyword") keyword: String,
        @Query("classificationName") classificationName: String = "music",
        @Query("apikey") apiKey: String,
        @Query("latlong") latlong: String? = null,
        @Query("radius") radius: Int? = null,
        @Query("unit") unit: String = "miles",
        @Query("size") size: Int = 20,
        @Query("sort") sort: String = "date,asc",
        @Query("startDateTime") startDateTime: String? = null,
        @Query("endDateTime") endDateTime: String? = null,
    ): TmSearchResponse

    @GET("discovery/v2/events.json")
    suspend fun getLocalEvents(
        @Query("classificationName") classificationName: String = "music",
        @Query("apikey") apiKey: String,
        @Query("latlong") latlong: String,
        @Query("radius") radius: Int = 50,
        @Query("unit") unit: String = "miles",
        @Query("size") size: Int = 50,
        @Query("sort") sort: String = "date,asc",
        @Query("startDateTime") startDateTime: String? = null,
    ): TmSearchResponse
}

// ── Response Models ──────────────────────────────────────────

@Serializable
data class TmSearchResponse(
    @SerialName("_embedded") val embedded: TmEmbedded? = null,
    val page: TmPage? = null,
)

@Serializable
data class TmEmbedded(
    val events: List<TmEvent> = emptyList(),
)

@Serializable
data class TmPage(
    val size: Int = 0,
    val totalElements: Int = 0,
    val totalPages: Int = 0,
    val number: Int = 0,
)

@Serializable
data class TmEvent(
    val id: String,
    val name: String,
    val type: String? = null,
    val url: String? = null,
    val dates: TmDates? = null,
    val images: List<TmImage> = emptyList(),
    @SerialName("_embedded") val embedded: TmEventEmbedded? = null,
)

@Serializable
data class TmDates(
    val start: TmStartDate? = null,
    val status: TmDateStatus? = null,
)

@Serializable
data class TmStartDate(
    val localDate: String? = null,
    val localTime: String? = null,
    val dateTime: String? = null,
)

@Serializable
data class TmDateStatus(
    val code: String? = null, // "onsale", "offsale", "cancelled", "postponed", "rescheduled"
)

@Serializable
data class TmImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val ratio: String? = null, // "16_9", "3_2", "4_3"
)

@Serializable
data class TmEventEmbedded(
    val venues: List<TmVenue> = emptyList(),
    val attractions: List<TmAttraction> = emptyList(),
)

@Serializable
data class TmVenue(
    val id: String? = null,
    val name: String? = null,
    val city: TmCity? = null,
    val state: TmState? = null,
    val country: TmCountry? = null,
    val location: TmLocation? = null,
)

@Serializable
data class TmCity(val name: String? = null)

@Serializable
data class TmState(
    val name: String? = null,
    val stateCode: String? = null,
)

@Serializable
data class TmCountry(
    val name: String? = null,
    val countryCode: String? = null,
)

@Serializable
data class TmLocation(
    val longitude: String? = null,
    val latitude: String? = null,
)

@Serializable
data class TmAttraction(
    val id: String? = null,
    val name: String? = null,
)
