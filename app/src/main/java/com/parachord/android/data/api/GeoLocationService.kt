package com.parachord.android.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GeoIP + Nominatim geocoding service matching the desktop's location detection.
 *
 * Desktop flow:
 * 1. Try GeoIP services (ipapi.co, ip-api.com, ipwho.is) for lat/lng
 * 2. Reverse geocode with Nominatim to get city name
 * 3. Forward geocode with Nominatim for user-typed location search
 */
@Singleton
class GeoLocationService @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "GeoLocationService"
        private val json = Json { ignoreUnknownKeys = true }
    }

    data class GeoCoords(val lat: Double, val lng: Double)
    data class GeoLocation(val lat: Double, val lng: Double, val displayName: String)

    /**
     * Detect user's location via GeoIP services.
     * Tries multiple providers with fallback (matching desktop).
     */
    suspend fun detectLocationByIp(): GeoLocation? = withContext(Dispatchers.IO) {
        val coords = tryIpApiCo()
            ?: tryIpApi()
            ?: tryIpWhoIs()

        if (coords == null) {
            Log.w(TAG, "All GeoIP services failed")
            return@withContext null
        }

        // Reverse geocode to get a city name
        val cityName = reverseGeocode(coords.lat, coords.lng)
        GeoLocation(coords.lat, coords.lng, cityName ?: "%.2f, %.2f".format(coords.lat, coords.lng))
    }

    /**
     * Search for locations by query string using Nominatim (OpenStreetMap).
     * Returns a list of suggestions with coordinates.
     */
    suspend fun searchLocations(query: String): List<GeoLocation> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val url = "https://nominatim.openstreetmap.org/search" +
                "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&format=json&limit=5&addressdetails=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Parachord/0.1.0 (parachord-android)")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            if (!response.isSuccessful) return@withContext emptyList()

            val results = json.decodeFromString<List<NominatimSearchResult>>(body)
            results.mapNotNull { result ->
                val lat = result.lat.toDoubleOrNull() ?: return@mapNotNull null
                val lon = result.lon.toDoubleOrNull() ?: return@mapNotNull null
                val name = buildNominatimDisplayName(result)
                GeoLocation(lat, lon, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim search failed for '$query'", e)
            emptyList()
        }
    }

    /**
     * Reverse geocode coordinates to a display name via Nominatim.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse" +
                "?lat=$lat&lon=$lng&format=json&zoom=10"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Parachord/0.1.0 (parachord-android)")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) return@withContext null

            val result = json.decodeFromString<NominatimReverseResult>(body)
            val addr = result.address ?: return@withContext null
            val city = addr.city ?: addr.town ?: addr.village ?: addr.county
            val state = addr.state
            val country = addr.country
            buildString {
                city?.let { append(it) }
                state?.let { if (isNotEmpty()) append(", "); append(it) }
                if (isEmpty()) country?.let { append(it) }
            }.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed", e)
            null
        }
    }

    // ── GeoIP Providers (matching desktop fallback chain) ────────

    private fun tryIpApiCo(): GeoCoords? = try {
        val request = Request.Builder().url("https://ipapi.co/json/").get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (response.isSuccessful && body != null) {
            val result = json.decodeFromString<IpApiCoResponse>(body)
            if (result.latitude != null && result.longitude != null) {
                GeoCoords(result.latitude, result.longitude)
            } else null
        } else null
    } catch (e: Exception) {
        Log.d(TAG, "ipapi.co failed: ${e.message}")
        null
    }

    private fun tryIpApi(): GeoCoords? = try {
        val request = Request.Builder()
            .url("http://ip-api.com/json/?fields=lat,lon,status")
            .get()
            .build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (response.isSuccessful && body != null) {
            val result = json.decodeFromString<IpApiResponse>(body)
            if (result.status == "success" && result.lat != null && result.lon != null) {
                GeoCoords(result.lat, result.lon)
            } else null
        } else null
    } catch (e: Exception) {
        Log.d(TAG, "ip-api.com failed: ${e.message}")
        null
    }

    private fun tryIpWhoIs(): GeoCoords? = try {
        val request = Request.Builder().url("https://ipwho.is/").get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (response.isSuccessful && body != null) {
            val result = json.decodeFromString<IpWhoIsResponse>(body)
            if (result.success == true && result.latitude != null && result.longitude != null) {
                GeoCoords(result.latitude, result.longitude)
            } else null
        } else null
    } catch (e: Exception) {
        Log.d(TAG, "ipwho.is failed: ${e.message}")
        null
    }

    private fun buildNominatimDisplayName(result: NominatimSearchResult): String {
        val addr = result.address
        if (addr != null) {
            val city = addr.city ?: addr.town ?: addr.village ?: addr.county
            val state = addr.state
            val country = addr.country
            return buildString {
                city?.let { append(it) }
                state?.let { if (isNotEmpty()) append(", "); append(it) }
                if (isEmpty()) country?.let { append(it) }
            }.ifEmpty { result.displayName }
        }
        return result.displayName
    }
}

// ── GeoIP Response Models ──────────────────────────────────────

@Serializable
private data class IpApiCoResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
private data class IpApiResponse(
    val status: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
private data class IpWhoIsResponse(
    val success: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

// ── Nominatim Response Models ──────────────────────────────────

@Serializable
data class NominatimSearchResult(
    val lat: String = "",
    val lon: String = "",
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String = "",
    val address: NominatimAddress? = null,
)

@Serializable
data class NominatimReverseResult(
    val address: NominatimAddress? = null,
)

@Serializable
data class NominatimAddress(
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
)
