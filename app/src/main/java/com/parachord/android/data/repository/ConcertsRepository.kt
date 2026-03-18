package com.parachord.android.data.repository

import android.content.Context
import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.SeatGeekApi
import com.parachord.android.data.api.TicketmasterApi
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified concert/event data from Ticketmaster + SeatGeek.
 * Results are merged and deduplicated by event name + date + venue.
 */
@Serializable
data class ConcertEvent(
    val id: String,
    val name: String,
    val artistName: String? = null,
    val venueName: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val displayLocation: String? = null,
    val date: String? = null,         // ISO local date yyyy-MM-dd
    val time: String? = null,         // HH:mm local time
    val dateTime: String? = null,     // ISO datetime
    val imageUrl: String? = null,
    val ticketUrl: String? = null,
    val source: String = "ticketmaster", // "ticketmaster" or "seatgeek"
    val status: String? = null,       // "onsale", "offsale", "cancelled", etc.
) {
    /** Formatted display date (e.g. "Sat, Mar 22, 2026"). */
    val displayDate: String
        get() {
            val d = date ?: return ""
            return try {
                val parsed = LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE)
                parsed.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
            } catch (_: Exception) { d }
        }

    /** Formatted display time (e.g. "8:00 PM"). */
    val displayTime: String
        get() {
            val t = time ?: return ""
            return try {
                val parts = t.split(":")
                val hour = parts[0].toInt()
                val min = parts[1]
                val ampm = if (hour >= 12) "PM" else "AM"
                val h12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                "$h12:$min $ampm"
            } catch (_: Exception) { t }
        }

    /** Location string like "New York, NY" or "London, GB". */
    val locationString: String
        get() = displayLocation ?: buildString {
            city?.let { append(it) }
            state?.let { if (isNotEmpty()) append(", "); append(it) }
            if (isEmpty()) country?.let { append(it) }
        }

    /** True if event is in the future. */
    val isUpcoming: Boolean
        get() {
            val d = date ?: return true
            return try {
                !LocalDate.parse(d).isBefore(LocalDate.now())
            } catch (_: Exception) { true }
        }
}

@Singleton
class ConcertsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ticketmasterApi: TicketmasterApi,
    private val seatGeekApi: SeatGeekApi,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "ConcertsRepo"
        private const val STALE_THRESHOLD = 30 * 60 * 1000L // 30 min
        private const val CACHE_FILE = "concerts_cache.json"
        private const val ARTIST_CACHE_PREFIX = "concerts_artist_"
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Local events cache ──────────────────────────────────────

    private var cachedLocalEvents: List<ConcertEvent>? = null
    private var localFetchedAt: Long = 0L
    private var diskCacheLoaded = false

    val cached: List<ConcertEvent>?
        get() {
            if (!diskCacheLoaded) loadDiskCache()
            return cachedLocalEvents
        }
    val isCacheStale: Boolean get() = System.currentTimeMillis() - localFetchedAt > STALE_THRESHOLD

    private fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return
            val wrapper = diskJson.decodeFromString<ConcertsDiskCache>(file.readText())
            cachedLocalEvents = wrapper.events
            localFetchedAt = wrapper.fetchedAt
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    private fun saveDiskCache(events: List<ConcertEvent>, fetchedAt: Long) {
        try {
            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(diskJson.encodeToString(ConcertsDiskCache(events, fetchedAt)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    // ── Artist tour dates cache ─────────────────────────────────

    private val artistEventsCache = mutableMapOf<String, Pair<Long, List<ConcertEvent>>>()

    /**
     * Quick check: does this artist have upcoming events?
     * Returns cached result if available, otherwise null (unknown).
     */
    fun hasUpcomingEvents(artistName: String): Boolean? {
        val (fetchedAt, events) = artistEventsCache[artistName.lowercase()] ?: return null
        if (System.currentTimeMillis() - fetchedAt > STALE_THRESHOLD * 2) return null
        return events.isNotEmpty()
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Get local events near the user's location.
     */
    fun getLocalEvents(
        lat: Double,
        lon: Double,
        radiusMiles: Int = 50,
        forceRefresh: Boolean = false,
    ): Flow<Resource<List<ConcertEvent>>> = flow {
        if (!diskCacheLoaded) loadDiskCache()
        val isStale = System.currentTimeMillis() - localFetchedAt > STALE_THRESHOLD

        if (!forceRefresh && !isStale && cachedLocalEvents != null) {
            emit(Resource.Success(cachedLocalEvents!!))
            return@flow
        }

        cachedLocalEvents?.let { emit(Resource.Success(it)) } ?: emit(Resource.Loading)

        try {
            val events = withContext(Dispatchers.IO) {
                fetchLocalEvents(lat, lon, radiusMiles)
            }
            cachedLocalEvents = events
            localFetchedAt = System.currentTimeMillis()
            saveDiskCache(events, localFetchedAt)
            emit(Resource.Success(events))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local events", e)
            if (cachedLocalEvents == null) {
                emit(Resource.Error("Failed to load concerts: ${e.message}"))
            }
        }
    }

    /**
     * Get upcoming events for a specific artist.
     */
    fun getArtistEvents(artistName: String): Flow<Resource<List<ConcertEvent>>> = flow {
        val cacheKey = artistName.lowercase()
        val cached = artistEventsCache[cacheKey]
        if (cached != null) {
            val (fetchedAt, events) = cached
            if (System.currentTimeMillis() - fetchedAt < STALE_THRESHOLD * 2) {
                emit(Resource.Success(events))
                return@flow
            }
            emit(Resource.Success(events))
        } else {
            emit(Resource.Loading)
        }

        try {
            val events = withContext(Dispatchers.IO) {
                fetchArtistEvents(artistName)
            }
            artistEventsCache[cacheKey] = System.currentTimeMillis() to events
            emit(Resource.Success(events))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load events for '$artistName'", e)
            if (cached == null) {
                emit(Resource.Error("Failed to load tour dates"))
            }
        }
    }

    /**
     * Lightweight check for "On Tour" — just checks if there are upcoming events.
     * Uses cache when available, fetches if not.
     */
    suspend fun checkOnTour(artistName: String): Boolean {
        // Check cache first
        val cacheKey = artistName.lowercase()
        val cached = artistEventsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < STALE_THRESHOLD * 2) {
            return cached.second.isNotEmpty()
        }

        return try {
            val events = withContext(Dispatchers.IO) { fetchArtistEvents(artistName) }
            artistEventsCache[cacheKey] = System.currentTimeMillis() to events
            events.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "On-tour check failed for '$artistName'", e)
            false
        }
    }

    // ── Private fetching ────────────────────────────────────────

    private suspend fun fetchLocalEvents(
        lat: Double,
        lon: Double,
        radiusMiles: Int,
    ): List<ConcertEvent> = coroutineScope {
        val now = LocalDateTime.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
        val latlong = "$lat,$lon"

        val tmDeferred = async {
            try {
                val tmKey = BuildConfig.TICKETMASTER_API_KEY
                if (tmKey.isBlank()) return@async emptyList()
                val response = ticketmasterApi.getLocalEvents(
                    apiKey = tmKey,
                    latlong = latlong,
                    radius = radiusMiles,
                    startDateTime = now,
                )
                response.embedded?.events?.map { it.toConcertEvent() } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Ticketmaster local events failed", e)
                emptyList()
            }
        }

        val sgDeferred = async {
            try {
                val sgKey = BuildConfig.SEATGEEK_CLIENT_ID
                if (sgKey.isBlank()) return@async emptyList()
                val response = seatGeekApi.getLocalEvents(
                    clientId = sgKey,
                    lat = lat,
                    lon = lon,
                    range = "${radiusMiles}mi",
                    datetimeGte = LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                    ),
                )
                response.events.map { it.toConcertEvent() }
            } catch (e: Exception) {
                Log.w(TAG, "SeatGeek local events failed", e)
                emptyList()
            }
        }

        val tmEvents = tmDeferred.await()
        val sgEvents = sgDeferred.await()
        mergeAndDedupe(tmEvents + sgEvents)
    }

    private suspend fun fetchArtistEvents(artistName: String): List<ConcertEvent> = coroutineScope {
        val tmDeferred = async {
            try {
                val tmKey = BuildConfig.TICKETMASTER_API_KEY
                if (tmKey.isBlank()) return@async emptyList()
                val now = LocalDateTime.now(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
                val response = ticketmasterApi.searchEvents(
                    keyword = artistName,
                    apiKey = tmKey,
                    startDateTime = now,
                    size = 30,
                )
                response.embedded?.events
                    ?.filter { event ->
                        event.embedded?.attractions?.any {
                            it.name.equals(artistName, ignoreCase = true)
                        } == true
                    }
                    ?.map { it.toConcertEvent() }
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Ticketmaster artist search failed for '$artistName'", e)
                emptyList()
            }
        }

        val sgDeferred = async {
            try {
                val sgKey = BuildConfig.SEATGEEK_CLIENT_ID
                if (sgKey.isBlank()) return@async emptyList()
                val now = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                val response = seatGeekApi.searchEvents(
                    query = artistName,
                    clientId = sgKey,
                    datetimeGte = now,
                    perPage = 30,
                )
                response.events
                    .filter { event ->
                        event.performers.any {
                            it.name.equals(artistName, ignoreCase = true)
                        }
                    }
                    .map { it.toConcertEvent() }
            } catch (e: Exception) {
                Log.w(TAG, "SeatGeek artist search failed for '$artistName'", e)
                emptyList()
            }
        }

        val tmEvents = tmDeferred.await()
        val sgEvents = sgDeferred.await()
        mergeAndDedupe(tmEvents + sgEvents)
    }

    private fun mergeAndDedupe(events: List<ConcertEvent>): List<ConcertEvent> {
        val seen = mutableSetOf<String>()
        return events
            .filter { event ->
                val key = "${event.name.lowercase()}|${event.date}|${event.venueName?.lowercase()}"
                seen.add(key)
            }
            .filter { it.isUpcoming }
            .sortedBy { it.date ?: "" }
    }
}

// ── Extension mappers ───────────────────────────────────────────

private fun com.parachord.android.data.api.TmEvent.toConcertEvent(): ConcertEvent {
    val venue = embedded?.venues?.firstOrNull()
    val attraction = embedded?.attractions?.firstOrNull()
    // Pick best image — prefer 16:9 ratio, largest
    val image = images
        .sortedByDescending { it.width }
        .firstOrNull { it.ratio == "16_9" }
        ?: images.maxByOrNull { it.width }

    return ConcertEvent(
        id = "tm-$id",
        name = name,
        artistName = attraction?.name,
        venueName = venue?.name,
        city = venue?.city?.name,
        state = venue?.state?.stateCode ?: venue?.state?.name,
        country = venue?.country?.countryCode,
        displayLocation = buildString {
            venue?.city?.name?.let { append(it) }
            venue?.state?.stateCode?.let { if (isNotEmpty()) append(", "); append(it) }
        }.ifEmpty { null },
        date = dates?.start?.localDate,
        time = dates?.start?.localTime,
        dateTime = dates?.start?.dateTime,
        imageUrl = image?.url,
        ticketUrl = url,
        source = "ticketmaster",
        status = dates?.status?.code,
    )
}

private fun com.parachord.android.data.api.SgEvent.toConcertEvent(): ConcertEvent {
    val mainPerformer = performers.firstOrNull()
    val dateStr = datetimeLocal?.take(10) // "2026-03-22T20:00:00" → "2026-03-22"
    val timeStr = datetimeLocal?.let {
        if (it.length >= 16) it.substring(11, 16) else null // "20:00"
    }

    return ConcertEvent(
        id = "sg-$id",
        name = title,
        artistName = mainPerformer?.name,
        venueName = venue?.name,
        city = venue?.city,
        state = venue?.state,
        country = venue?.country,
        displayLocation = venue?.displayLocation,
        date = dateStr,
        time = timeStr,
        dateTime = datetimeUtc,
        imageUrl = mainPerformer?.image,
        ticketUrl = url,
        source = "seatgeek",
    )
}

@Serializable
private data class ConcertsDiskCache(
    val events: List<ConcertEvent>,
    val fetchedAt: Long,
)
