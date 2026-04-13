package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ticketmaster Discovery API client.
 * Base URL: https://app.ticketmaster.com/
 * Auth: API key as query parameter.
 */
class TicketmasterClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://app.ticketmaster.com/discovery/v2"
    }

    suspend fun searchEvents(
        keyword: String,
        apiKey: String,
        classificationName: String = "music",
        latlong: String? = null,
        radius: Int? = null,
        unit: String = "miles",
        size: Int = 20,
        sort: String = "date,asc",
        startDateTime: String? = null,
        endDateTime: String? = null,
    ): TmSearchResponse = httpClient.get("$BASE_URL/events.json") {
        parameter("keyword", keyword)
        parameter("classificationName", classificationName)
        parameter("apikey", apiKey)
        if (latlong != null) parameter("latlong", latlong)
        if (radius != null) parameter("radius", radius)
        parameter("unit", unit)
        parameter("size", size)
        parameter("sort", sort)
        if (startDateTime != null) parameter("startDateTime", startDateTime)
        if (endDateTime != null) parameter("endDateTime", endDateTime)
    }.body()

    suspend fun searchAttractions(
        keyword: String,
        apiKey: String,
        classificationName: String = "music",
        size: Int = 5,
    ): TmAttractionsResponse = httpClient.get("$BASE_URL/attractions.json") {
        parameter("keyword", keyword)
        parameter("classificationName", classificationName)
        parameter("size", size)
        parameter("apikey", apiKey)
    }.body()

    suspend fun getEventsByAttraction(
        attractionId: String,
        apiKey: String,
        classificationName: String = "music",
        sort: String = "date,asc",
        size: Int = 50,
        startDateTime: String? = null,
        latlong: String? = null,
        radius: Int? = null,
        unit: String = "miles",
    ): TmSearchResponse = httpClient.get("$BASE_URL/events.json") {
        parameter("attractionId", attractionId)
        parameter("classificationName", classificationName)
        parameter("sort", sort)
        parameter("size", size)
        if (startDateTime != null) parameter("startDateTime", startDateTime)
        if (latlong != null) parameter("latlong", latlong)
        if (radius != null) parameter("radius", radius)
        parameter("unit", unit)
        parameter("apikey", apiKey)
    }.body()

    suspend fun getLocalEvents(
        apiKey: String,
        latlong: String,
        classificationName: String = "music",
        radius: Int = 50,
        unit: String = "miles",
        size: Int = 50,
        sort: String = "date,asc",
        startDateTime: String? = null,
    ): TmSearchResponse = httpClient.get("$BASE_URL/events.json") {
        parameter("classificationName", classificationName)
        parameter("apikey", apiKey)
        parameter("latlong", latlong)
        parameter("radius", radius)
        parameter("unit", unit)
        parameter("size", size)
        parameter("sort", sort)
        if (startDateTime != null) parameter("startDateTime", startDateTime)
    }.body()
}

// ── Response Models ──────────────────────────────────────────────────

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
    val totalElements: Int = 0,
    val totalPages: Int = 0,
)

@Serializable
data class TmEvent(
    val id: String,
    val name: String,
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
    val code: String? = null,
)

@Serializable
data class TmImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
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
    val url: String? = null,
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
    val latitude: String? = null,
    val longitude: String? = null,
)

@Serializable
data class TmAttraction(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class TmAttractionsResponse(
    @SerialName("_embedded") val embedded: TmAttractionsEmbedded? = null,
)

@Serializable
data class TmAttractionsEmbedded(
    val attractions: List<TmAttraction> = emptyList(),
)
