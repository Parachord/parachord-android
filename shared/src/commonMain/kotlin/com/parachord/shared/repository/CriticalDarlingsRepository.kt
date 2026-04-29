package com.parachord.shared.repository

import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.metadata.MusicBrainzProvider
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for Critical Darlings — top-rated albums from leading music publications.
 *
 * Mirrors the desktop app's `loadCriticsPicks()` / `parseCriticsPicksRSS()` implementation:
 * 1. Fetch RSS feed from rssground.com/p/uncoveries
 * 2. Parse each item as "Album Title by Artist Name"
 * 3. Progressively fetch album art via Cover Art Archive (MusicBrainz release search → MBID → CAA)
 *
 * Migrated from OkHttp + `XmlPullParser` + `java.util.Date` to:
 *  - Shared Ktor `HttpClient` for the RSS fetch
 *  - Regex-based RSS item extraction (RSS structure is simple enough that
 *    pulling in a KMP XML library — `xmlutil` — for one feed isn't worth
 *    the dep weight; CDATA sections are handled inline)
 *  - `pubDate: Date?` dropped entirely — the field was never displayed
 *    by the UI. The custom `KSerializer` surrogate that handled
 *    `Date → pubDateMs` round-tripping went with it; existing on-disk
 *    caches with the `pubDateMs` key are tolerated via
 *    `ignoreUnknownKeys = true` (worst case is one stale-cache miss).
 *
 * File I/O for the disk cache flows through the same suspend-lambda
 * pattern established by `ConcertsRepository` and `FreshDropsRepository`.
 */
class CriticalDarlingsRepository(
    private val httpClient: HttpClient,
    private val musicBrainzClient: MusicBrainzClient,
    /** Read JSON from `critical_darlings_cache.json`; null if missing/fails. */
    private val cacheRead: suspend () -> String?,
    /** Write JSON to `critical_darlings_cache.json`. Failures are swallowed. */
    private val cacheWrite: suspend (String) -> Unit,
) {
    companion object {
        private const val TAG = "CriticalDarlingsRepo"
        private const val RSS_URL = "https://www.rssground.com/p/uncoveries"
        /** Short interval to prevent re-fetching when navigating back and forth quickly. */
        private const val MIN_REFETCH_INTERVAL = 5 * 60 * 1000L // 5 minutes
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache. */
    private var cachedAlbums: List<CriticsPickAlbum>? = null
    private var lastFetchedAt: Long = 0L
    private var diskCacheLoaded = false

    /**
     * Cached albums available without a coroutine context (for ViewModel
     * `init` blocks). Lazy disk load triggered by next suspend call —
     * same trade-off as `ConcertsRepository.cached`.
     */
    val cached: List<CriticsPickAlbum>? get() = cachedAlbums

    private suspend fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val body = cacheRead() ?: return
            val wrapper = diskJson.decodeFromString<CriticalDarlingsDiskCache>(body)
            cachedAlbums = wrapper.albums
            lastFetchedAt = wrapper.fetchedAt
            Log.d(TAG, "Loaded ${wrapper.albums.size} cached critics' picks from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    private suspend fun saveDiskCache(albums: List<CriticsPickAlbum>, fetchedAt: Long) {
        try {
            cacheWrite(diskJson.encodeToString(CriticalDarlingsDiskCache(albums, fetchedAt)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /**
     * Get critics' picks albums, with progressive album art loading.
     * Emits an initial list (without art), then re-emits as each album's art is resolved.
     */
    fun getCriticsPicks(forceRefresh: Boolean = false): Flow<Resource<List<CriticsPickAlbum>>> = flow {
        if (!diskCacheLoaded) loadDiskCache()
        try {
            val now = currentTimeMillis()
            val recentlyFetched = now - lastFetchedAt < MIN_REFETCH_INTERVAL

            // Show cache immediately (stale-while-revalidate)
            if (cachedAlbums != null) {
                emit(Resource.Success(cachedAlbums!!))
            } else {
                emit(Resource.Loading)
            }

            // Skip re-fetch if we fetched very recently (prevents hammering on quick nav)
            if (!forceRefresh && recentlyFetched && cachedAlbums != null) {
                return@flow
            }

            val albums = fetchAndParseRSS()

            if (albums.isEmpty()) {
                // Keep showing cached data if available, only error on truly empty
                if (cachedAlbums == null) {
                    emit(Resource.Error("No critics' picks available"))
                }
                return@flow
            }

            // Carry over album art from cached albums so we don't re-fetch everything
            val oldArtByKey = cachedAlbums?.associateBy(
                { "${it.title.lowercase()}|${it.artist.lowercase()}" },
                { it.albumArt },
            ) ?: emptyMap()
            val mergedAlbums = albums.map { album ->
                val cachedArt = oldArtByKey["${album.title.lowercase()}|${album.artist.lowercase()}"]
                if (cachedArt != null) album.copy(albumArt = cachedArt) else album
            }

            cachedAlbums = mergedAlbums
            lastFetchedAt = now
            saveDiskCache(mergedAlbums, now)
            emit(Resource.Success(mergedAlbums))

            // Progressively fetch album art only for albums that still need it
            val mutableAlbums = mergedAlbums.toMutableList()
            val toEnrich = mutableAlbums.withIndex().filter { it.value.albumArt == null }
            if (toEnrich.isEmpty()) return@flow

            for ((index, album) in toEnrich) {
                try {
                    val artUrl = fetchAlbumArt(album.title, album.artist)
                    if (artUrl != null) {
                        mutableAlbums[index] = album.copy(albumArt = artUrl)
                        cachedAlbums = mutableAlbums.toList()
                        emit(Resource.Success(mutableAlbums.toList()))
                    }
                    // Rate limit MusicBrainz requests (1 req/sec policy)
                    delay(200)
                } catch (_: Exception) { /* skip art for this album */ }
            }
            // Save final enriched data to disk
            cachedAlbums?.let { saveDiskCache(it, lastFetchedAt) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load critics' picks", e)
            emit(Resource.Error("Failed to load critics' picks"))
        }
    }

    /**
     * Fetch and parse the RSS feed via the shared Ktor client.
     * Each RSS item title is in the format "Album Title by Artist Name".
     * Mirrors desktop's `parseCriticsPicksRSS()`.
     */
    private suspend fun fetchAndParseRSS(): List<CriticsPickAlbum> {
        val response = try {
            httpClient.get(RSS_URL)
        } catch (e: Exception) {
            Log.w(TAG, "RSS fetch failed: ${e.message}")
            return emptyList()
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "RSS fetch failed: ${response.status.value}")
            return emptyList()
        }
        return parseRSS(response.bodyAsText())
    }

    /**
     * Regex-based RSS item extraction. The feed structure is simple
     * (`<item><title>...</title><link>...</link><description>...</description>...</item>`)
     * and stable enough that a full XML parser isn't worth the KMP dep
     * weight. CDATA sections are stripped inline.
     */
    private fun parseRSS(xml: String): List<CriticsPickAlbum> {
        val albums = mutableListOf<CriticsPickAlbum>()
        val seen = mutableSetOf<String>()
        try {
            val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            val linkRegex = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
            val descRegex = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)

            for (itemMatch in itemRegex.findAll(xml)) {
                val itemXml = itemMatch.groupValues[1]
                val title = titleRegex.find(itemXml)?.groupValues?.get(1)?.let(::stripCdata)?.trim() ?: continue
                val link = linkRegex.find(itemXml)?.groupValues?.get(1)?.let(::stripCdata)?.trim()
                val description = descRegex.find(itemXml)?.groupValues?.get(1)?.let(::stripCdata)?.trim() ?: ""

                val parsed = parseTitle(title) ?: continue
                val id = "${parsed.first.lowercase()}|${parsed.second.lowercase()}"
                    .replace(Regex("[^a-z0-9|]"), "-")
                if (!seen.add(id)) continue

                albums.add(
                    CriticsPickAlbum(
                        id = "critics-$id",
                        title = parsed.first,
                        artist = parsed.second,
                        link = link,
                        description = cleanHtml(description),
                        spotifyUrl = extractSpotifyUrl(description),
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS parse error", e)
        }
        Log.d(TAG, "Parsed ${albums.size} critics' picks from RSS")
        return albums
    }

    /** Strip `<![CDATA[ ... ]]>` wrappers from extracted XML text. */
    private fun stripCdata(s: String): String =
        s.replace("<![CDATA[", "").replace("]]>", "")

    /**
     * Parse "Album Title by Artist Name" format from RSS item title.
     * Returns (albumTitle, artistName) or null if format doesn't match.
     */
    private fun parseTitle(raw: String): Pair<String, String>? {
        // Normalise whitespace — the RSS feed uses literal newlines between album and "by Artist"
        val normalised = raw.replace(Regex("\\s+"), " ").trim()
        // Desktop uses: title split on " by " — last occurrence to handle "Stand By Me by Ben E. King"
        val idx = normalised.lastIndexOf(" by ")
        if (idx <= 0) return null
        val album = normalised.substring(0, idx).trim()
        val artist = normalised.substring(idx + 4).trim()
        if (album.isBlank() || artist.isBlank()) return null
        return album to artist
    }

    /** Extract Spotify URL from HTML description (matching desktop). */
    private fun extractSpotifyUrl(html: String): String? {
        val regex = Regex("""https?://open\.spotify\.com/album/[a-zA-Z0-9]+""")
        return regex.find(html)?.value
    }

    /** Strip HTML tags, decode entities, and remove leftover URLs. */
    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    /**
     * Fetch album art via MusicBrainz release search → Cover Art Archive.
     * Mirrors desktop's `getAlbumArt()` approach.
     */
    private suspend fun fetchAlbumArt(albumTitle: String, artistName: String): String? {
        return try {
            val query = "\"$albumTitle\" AND artist:\"$artistName\""
            val results = musicBrainzClient.searchReleases(query, limit = 1)
            val release = results.releases.firstOrNull() ?: return null
            // Coil follows the redirect to the actual image
            MusicBrainzProvider.coverArtUrl(release.id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch art for '$albumTitle' by '$artistName'", e)
            null
        }
    }
}

/** Disk cache wrapper for JSON serialization. */
@Serializable
private data class CriticalDarlingsDiskCache(
    val albums: List<CriticsPickAlbum>,
    val fetchedAt: Long,
)

/**
 * A critics' pick album from the RSS feed.
 *
 * The previous `pubDate: Date?` field + custom `KSerializer` surrogate
 * was removed in the move to shared — the field was never read by the
 * UI, only parsed and stored. Existing on-disk caches with the
 * `pubDateMs` key are tolerated via `ignoreUnknownKeys = true`; worst
 * case is one stale-cache miss.
 */
@Serializable
data class CriticsPickAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val link: String? = null,
    val description: String = "",
    val spotifyUrl: String? = null,
    val albumArt: String? = null,
)
