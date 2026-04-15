package com.parachord.android.data.repository

import android.content.Context
import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.SeatGeekApi
import com.parachord.android.data.api.TicketmasterApi
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val lineup: List<String> = emptyList(), // All performing artists
    val source: String = "ticketmaster", // "ticketmaster" or "seatgeek"
    val status: String? = null,       // "onsale", "offsale", "cancelled", etc.
    val artistSource: String? = null, // "collection", "library", or "history"
    val ticketSources: List<TicketSource> = emptyList(), // merged ticket links from multiple providers
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

    /** Location string like "New York, NY, United States" or "London, United Kingdom". */
    val locationString: String
        get() = buildString {
            val base = displayLocation ?: buildString {
                city?.let { append(it) }
                state?.let { if (isNotEmpty()) append(", "); append(it) }
            }
            append(base)
            val countryName = country?.let { resolveCountryName(it) }
            if (countryName != null && !toString().contains(countryName, ignoreCase = true)) {
                if (isNotEmpty()) append(", ")
                append(countryName)
            } else if (countryName != null && isEmpty()) {
                append(countryName)
            }
        }

    companion object {
        /** Convert a country code (e.g. "US") or name to a full display name. */
        private fun resolveCountryName(country: String): String {
            if (country.length == 2) {
                return try {
                    java.util.Locale("", country).displayCountry
                } catch (_: Exception) { country }
            }
            return country
        }
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

@Serializable
data class TicketSource(
    val source: String,     // "ticketmaster" or "seatgeek"
    val ticketUrl: String,
    val label: String,      // "Ticketmaster" or "SeatGeek"
)

/**
 * Artist gathered from the user's collection or listening history,
 * used to personalize concert recommendations (matching desktop's gatherConcertsArtists).
 */
data class ConcertArtist(
    val name: String,
    val source: String,     // "collection", "library", or "history"
    val imageUrl: String? = null,
)

class ConcertsRepository constructor(
    private val context: Context,
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

    /** Prefer user-provided key from SettingsStore, fall back to BuildConfig. */
    private suspend fun ticketmasterKey(): String =
        settingsStore.getTicketmasterApiKey()?.takeIf { it.isNotBlank() }
            ?: BuildConfig.TICKETMASTER_API_KEY

    private suspend fun seatGeekKey(): String =
        settingsStore.getSeatGeekClientId()?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SEATGEEK_CLIENT_ID

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
     * Lightweight check for "On Tour" — checks if there are upcoming events
     * near the user's selected concert area. If no location is configured,
     * falls back to checking for any upcoming events globally (matching
     * desktop's `if (!concertsLocationCoords) return true` fallback).
     * Uses cache when available, fetches if not.
     */
    suspend fun checkOnTour(
        artistName: String,
        lat: Double? = null,
        lon: Double? = null,
        radiusMiles: Int = 50,
    ): Boolean {
        val cacheKey = artistName.lowercase()
        val cached = artistEventsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < STALE_THRESHOLD * 2) {
            return cached.second.isNotEmpty()
        }

        return try {
            // If no location configured, query without coords (any show counts)
            val events = withContext(Dispatchers.IO) {
                fetchArtistEvents(artistName, lat, lon, radiusMiles)
            }
            artistEventsCache[cacheKey] = System.currentTimeMillis() to events
            events.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "On-tour check failed for '$artistName'", e)
            false
        }
    }

    /**
     * Get personalized events for the user's artists, matching desktop's gatherConcertsArtists.
     * Searches for concerts by artists from the user's collection and listening history.
     * Uses a concurrency pool (max 5 parallel requests) matching desktop behavior.
     */
    fun getPersonalizedEvents(
        artists: List<ConcertArtist>,
        lat: Double? = null,
        lon: Double? = null,
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

        if (artists.isEmpty()) {
            emit(Resource.Success(emptyList()))
            return@flow
        }

        try {
            val events = withContext(Dispatchers.IO) {
                fetchPersonalizedEvents(artists, lat, lon, radiusMiles)
            }
            cachedLocalEvents = events
            localFetchedAt = System.currentTimeMillis()
            saveDiskCache(events, localFetchedAt)
            emit(Resource.Success(events))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load personalized events", e)
            if (cachedLocalEvents == null) {
                emit(Resource.Error("Failed to load concerts: ${e.message}"))
            }
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

        val tmKey = ticketmasterKey()
        val sgKey = seatGeekKey()

        val tmDeferred = async {
            try {
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

    /**
     * Fetch personalized events: query each artist with concurrency limit of 5
     * (matching desktop's concurrency pool for multi-artist concert discovery).
     */
    private suspend fun fetchPersonalizedEvents(
        artists: List<ConcertArtist>,
        lat: Double? = null,
        lon: Double? = null,
        radiusMiles: Int = 50,
    ): List<ConcertEvent> = coroutineScope {
        val semaphore = Semaphore(5) // Max 5 concurrent requests (matching desktop)
        val allEvents = artists.map { artist ->
            async {
                semaphore.withPermit {
                    try {
                        val events = fetchArtistEvents(artist.name, lat, lon, radiusMiles)
                        // Tag each event with the artist source
                        events.map { it.copy(artistSource = artist.source) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch events for '${artist.name}'", e)
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()

        mergeAndDedupe(allEvents)
    }

    /**
     * Fetch artist events using two-step resolution (matching desktop):
     * 1. Resolve artist name to attraction ID / performer slug
     * 2. Fetch events by that ID/slug (more precise than keyword search)
     */
    private suspend fun fetchArtistEvents(
        artistName: String,
        lat: Double? = null,
        lon: Double? = null,
        radiusMiles: Int = 50,
    ): List<ConcertEvent> = coroutineScope {
        val now = LocalDateTime.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

        val tmKey = ticketmasterKey()
        val sgKey = seatGeekKey()

        val tmDeferred = async {
            try {
                if (tmKey.isBlank()) return@async emptyList()

                // Step 1: Resolve artist to attraction ID
                val attractions = ticketmasterApi.searchAttractions(
                    keyword = artistName,
                    apiKey = tmKey,
                )
                val attraction = attractions.embedded?.attractions
                    ?.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                    ?: return@async emptyList()
                val attractionId = attraction.id ?: return@async emptyList()

                // Step 2: Fetch events by attraction ID
                val latlong = if (lat != null && lon != null) "$lat,$lon" else null
                val response = ticketmasterApi.getEventsByAttraction(
                    attractionId = attractionId,
                    apiKey = tmKey,
                    startDateTime = now,
                    latlong = latlong,
                    radius = if (latlong != null) radiusMiles else null,
                )
                response.embedded?.events?.map { it.toConcertEvent() } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Ticketmaster artist search failed for '$artistName'", e)
                emptyList()
            }
        }

        val sgDeferred = async {
            try {
                if (sgKey.isBlank()) return@async emptyList()

                // Step 1: Resolve artist to performer slug
                val performers = seatGeekApi.searchPerformers(
                    query = artistName,
                    clientId = sgKey,
                )
                val performer = performers.performers
                    .firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                    ?: return@async emptyList()
                val slug = performer.slug ?: return@async emptyList()

                // Step 2: Fetch events by performer slug
                val nowLocal = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                val response = seatGeekApi.getEventsByPerformer(
                    performerSlug = slug,
                    clientId = sgKey,
                    datetimeGte = nowLocal,
                    lat = lat,
                    lon = lon,
                    range = if (lat != null && lon != null) "${radiusMiles}mi" else null,
                )
                response.events.map { it.toConcertEvent() }
            } catch (e: Exception) {
                Log.w(TAG, "SeatGeek artist search failed for '$artistName'", e)
                emptyList()
            }
        }

        val tmEvents = tmDeferred.await()
        val sgEvents = sgDeferred.await()
        mergeAndDedupe(tmEvents + sgEvents)
    }

    /**
     * Deduplicate events using two strategies (matching desktop):
     * 1. Text-based: date + normalized artist + venue prefix (20 chars)
     * 2. Events with same date + artist but different venue spellings still merge
     * When duplicates are found, merge ticketSources from both providers.
     */
    private fun mergeAndDedupe(events: List<ConcertEvent>): List<ConcertEvent> {
        val merged = linkedMapOf<String, ConcertEvent>()
        for (event in events) {
            if (!event.isUpcoming) continue
            val artist = normalizeForDedup(event.artistName ?: event.name)
            val venuePrefix = normalizeForDedup(event.venueName ?: "").take(20)
            val key = "${event.date}-$artist-$venuePrefix"

            val existing = merged[key]
            if (existing != null) {
                // Merge ticket sources
                val existingSources = existing.ticketSources.ifEmpty {
                    listOf(TicketSource(existing.source, existing.ticketUrl ?: "", sourceLabel(existing.source)))
                }
                val newSource = TicketSource(event.source, event.ticketUrl ?: "", sourceLabel(event.source))
                val allSources = (existingSources + newSource).distinctBy { it.source }
                merged[key] = existing.copy(ticketSources = allSources)
            } else {
                // Initialize ticketSources from single source
                val sources = if (event.ticketUrl != null) {
                    listOf(TicketSource(event.source, event.ticketUrl, sourceLabel(event.source)))
                } else emptyList()
                merged[key] = event.copy(ticketSources = sources)
            }
        }
        return merged.values.sortedBy { it.date ?: "" }
    }

    private fun sourceLabel(source: String): String = when (source) {
        "ticketmaster" -> "Ticketmaster"
        "seatgeek" -> "SeatGeek"
        else -> source.replaceFirstChar { it.uppercase() }
    }

    /** Normalize string for deduplication: lowercase, strip non-alphanumeric except spaces. */
    private fun normalizeForDedup(str: String): String =
        str.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
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
        lineup = embedded?.attractions?.mapNotNull { it.name } ?: emptyList(),
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
        lineup = performers.mapNotNull { it.name },
        source = "seatgeek",
    )
}

@Serializable
private data class ConcertsDiskCache(
    val events: List<ConcertEvent>,
    val fetchedAt: Long,
)
