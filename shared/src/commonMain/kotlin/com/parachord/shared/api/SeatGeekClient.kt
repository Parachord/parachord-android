package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SeatGeek API client.
 * Base URL: https://api.seatgeek.com/2/
 * Auth: Client ID as query parameter.
 */
class SeatGeekClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://api.seatgeek.com/2"
    }

    suspend fun searchPerformers(
        query: String,
        clientId: String,
    ): SgPerformersResponse = httpClient.get("$BASE_URL/performers") {
        parameter("q", query)
        parameter("client_id", clientId)
    }.body()

    suspend fun getEventsByPerformer(
        performerSlug: String,
        clientId: String,
        datetimeGte: String? = null,
        sort: String = "datetime_local.asc",
        perPage: Int = 50,
        lat: Double? = null,
        lon: Double? = null,
        range: String? = null,
    ): SgSearchResponse = httpClient.get("$BASE_URL/events") {
        parameter("performers.slug", performerSlug)
        if (datetimeGte != null) parameter("datetime_utc.gte", datetimeGte)
        parameter("sort", sort)
        parameter("per_page", perPage)
        if (lat != null) parameter("lat", lat)
        if (lon != null) parameter("lon", lon)
        if (range != null) parameter("range", range)
        parameter("client_id", clientId)
    }.body()

    suspend fun searchEvents(
        query: String,
        clientId: String,
        type: String = "concert",
        lat: Double? = null,
        lon: Double? = null,
        range: String? = null,
        perPage: Int = 20,
        sort: String = "datetime_local.asc",
        datetimeGte: String? = null,
    ): SgSearchResponse = httpClient.get("$BASE_URL/events") {
        parameter("q", query)
        parameter("type", type)
        parameter("client_id", clientId)
        if (lat != null) parameter("lat", lat)
        if (lon != null) parameter("lon", lon)
        if (range != null) parameter("range", range)
        parameter("per_page", perPage)
        parameter("sort", sort)
        if (datetimeGte != null) parameter("datetime_local.gte", datetimeGte)
    }.body()

    suspend fun getLocalEvents(
        clientId: String,
        lat: Double,
        lon: Double,
        type: String = "concert",
        range: String = "50mi",
        perPage: Int = 50,
        sort: String = "datetime_local.asc",
        datetimeGte: String? = null,
    ): SgSearchResponse = httpClient.get("$BASE_URL/events") {
        parameter("type", type)
        parameter("client_id", clientId)
        parameter("lat", lat)
        parameter("lon", lon)
        parameter("range", range)
        parameter("per_page", perPage)
        parameter("sort", sort)
        if (datetimeGte != null) parameter("datetime_local.gte", datetimeGte)
    }.body()
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class SgSearchResponse(
    val events: List<SgEvent> = emptyList(),
    val meta: SgMeta? = null,
)

@Serializable
data class SgMeta(
    val total: Int = 0,
    @SerialName("per_page") val perPage: Int = 0,
    val page: Int = 0,
)

@Serializable
data class SgEvent(
    val id: Long,
    val title: String = "",
    @SerialName("short_title") val shortTitle: String? = null,
    val url: String? = null,
    @SerialName("datetime_local") val datetimeLocal: String? = null,
    @SerialName("datetime_utc") val datetimeUtc: String? = null,
    val venue: SgVenue? = null,
    val performers: List<SgPerformer> = emptyList(),
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
    @SerialName("short_name") val shortName: String? = null,
    val slug: String? = null,
    val image: String? = null,
)

@Serializable
data class SgPerformersResponse(
    val performers: List<SgPerformer> = emptyList(),
)
