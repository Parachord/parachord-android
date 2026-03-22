package com.parachord.android.data.repository

import android.content.Context
import android.util.Log
import com.parachord.android.data.api.MusicBrainzApi
import com.parachord.android.data.metadata.MusicBrainzProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Critical Darlings — top-rated albums from leading music publications.
 *
 * Mirrors the desktop app's `loadCriticsPicks()` / `parseCriticsPicksRSS()` implementation:
 * 1. Fetch RSS feed from rssground.com/p/uncoveries
 * 2. Parse each item as "Album Title by Artist Name"
 * 3. Progressively fetch album art via Cover Art Archive (MusicBrainz release search → MBID → CAA)
 *
 * Desktop caches art and uses a 4-hour staleness check. We keep it simple for now —
 * fetch on each screen open and cache in memory.
 */
@Singleton
class CriticalDarlingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val musicBrainzApi: MusicBrainzApi,
) {
    companion object {
        private const val TAG = "CriticalDarlingsRepo"
        private const val RSS_URL = "https://www.rssground.com/p/uncoveries"
        private const val CACHE_FILE = "critical_darlings_cache.json"
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache. */
    private var cachedAlbums: List<CriticsPickAlbum>? = null
    private var lastFetchedAt: Long = 0L
    private val STALE_THRESHOLD = 4 * 60 * 60 * 1000L // 4 hours (matching desktop)
    /** Short interval to prevent re-fetching when navigating back and forth quickly. */
    private val MIN_REFETCH_INTERVAL = 5 * 60 * 1000L // 5 minutes
    private var diskCacheLoaded = false

    /** Synchronous access to cached albums (for ViewModel initial state). */
    val cached: List<CriticsPickAlbum>?
        get() {
            if (!diskCacheLoaded) loadDiskCache()
            return cachedAlbums
        }

    private fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return
            val wrapper = diskJson.decodeFromString<CriticalDarlingsDiskCache>(file.readText())
            cachedAlbums = wrapper.albums
            lastFetchedAt = wrapper.fetchedAt
            Log.d(TAG, "Loaded ${wrapper.albums.size} cached critics' picks from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    private fun saveDiskCache(albums: List<CriticsPickAlbum>, fetchedAt: Long) {
        try {
            val wrapper = CriticalDarlingsDiskCache(albums = albums, fetchedAt = fetchedAt)
            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(diskJson.encodeToString(wrapper))
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
            val now = System.currentTimeMillis()
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

            val albums = withContext(Dispatchers.IO) { fetchAndParseRSS() }

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
                    val artUrl = withContext(Dispatchers.IO) {
                        fetchAlbumArt(album.title, album.artist)
                    }
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
     * Fetch and parse the RSS feed.
     * Each RSS item title is in the format "Album Title by Artist Name".
     * Mirrors desktop's `parseCriticsPicksRSS()`.
     */
    private fun fetchAndParseRSS(): List<CriticsPickAlbum> {
        val request = Request.Builder()
            .url(RSS_URL)
            .get()
            .addHeader("User-Agent", "Parachord/1.0 (https://parachord.app)")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()

        if (!response.isSuccessful || body == null) {
            Log.w(TAG, "RSS fetch failed: ${response.code}")
            return emptyList()
        }

        return parseRSS(body)
    }

    /**
     * Parse RSS XML into CriticsPickAlbum list.
     * Matches desktop's `parseCriticsPicksRSS()` parsing logic.
     */
    private fun parseRSS(xml: String): List<CriticsPickAlbum> {
        val albums = mutableListOf<CriticsPickAlbum>()
        val seen = mutableSetOf<String>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var title: String? = null
            var link: String? = null
            var description: String? = null
            var pubDate: String? = null
            var currentTag: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") {
                            inItem = true
                            title = null
                            link = null
                            description = null
                            pubDate = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem) {
                            val text = parser.text?.trim() ?: ""
                            when (currentTag) {
                                "title" -> title = (title ?: "") + text
                                "link" -> link = (link ?: "") + text
                                "description" -> description = (description ?: "") + text
                                "pubDate" -> pubDate = (pubDate ?: "") + text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && inItem) {
                            inItem = false
                            val parsed = parseTitle(title ?: "")
                            if (parsed != null) {
                                val id = "${parsed.first.lowercase()}|${parsed.second.lowercase()}"
                                    .replace(Regex("[^a-z0-9|]"), "-")
                                if (seen.add(id)) {
                                    // Extract Spotify URL from description (matching desktop)
                                    val spotifyUrl = extractSpotifyUrl(description ?: "")
                                    albums.add(
                                        CriticsPickAlbum(
                                            id = "critics-$id",
                                            title = parsed.first,
                                            artist = parsed.second,
                                            link = link,
                                            description = cleanHtml(description ?: ""),
                                            spotifyUrl = spotifyUrl,
                                            pubDate = parsePubDate(pubDate),
                                        ),
                                    )
                                }
                            }
                        }
                        if (parser.name == currentTag) {
                            currentTag = null
                        }
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS parse error", e)
        }

        Log.d(TAG, "Parsed ${albums.size} critics' picks from RSS")
        return albums
    }

    /**
     * Parse "Album Title by Artist Name" format from RSS item title.
     * Returns (albumTitle, artistName) or null if format doesn't match.
     */
    private fun parseTitle(raw: String): Pair<String, String>? {
        // Desktop uses: title split on " by " — last occurrence to handle "Something by Someone by Artist"
        val idx = raw.lastIndexOf(" by ")
        if (idx <= 0) return null
        val album = raw.substring(0, idx).trim()
        val artist = raw.substring(idx + 4).trim()
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
            // Remove URLs left over after stripping HTML (e.g. Spotify links)
            .replace(Regex("""https?://\S+"""), "")
            // Collapse multiple whitespace/newlines left by URL removal
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    /** Parse RSS pubDate format. */
    private fun parsePubDate(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
            format.parse(dateStr)
        } catch (_: Exception) {
            try {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
                format.parse(dateStr)
            } catch (_: Exception) { null }
        }
    }

    /**
     * Fetch album art via MusicBrainz release search → Cover Art Archive.
     * Mirrors desktop's `getAlbumArt()` approach.
     */
    private suspend fun fetchAlbumArt(albumTitle: String, artistName: String): String? {
        return try {
            // Search MusicBrainz for the release
            val query = "\"$albumTitle\" AND artist:\"$artistName\""
            val results = musicBrainzApi.searchReleases(query, limit = 1)
            val release = results.releases.firstOrNull() ?: return null
            // Return Cover Art Archive URL — Coil will follow the redirect
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
    val albums: List<@Serializable(with = CriticsPickAlbumSerializer::class) CriticsPickAlbum>,
    val fetchedAt: Long,
)

/** Custom serializer to handle java.util.Date in CriticsPickAlbum. */
private object CriticsPickAlbumSerializer : kotlinx.serialization.KSerializer<CriticsPickAlbum> {
    @Serializable
    private data class Surrogate(
        val id: String,
        val title: String,
        val artist: String,
        val link: String? = null,
        val description: String = "",
        val spotifyUrl: String? = null,
        val pubDateMs: Long? = null,
        val albumArt: String? = null,
    )

    override val descriptor = Surrogate.serializer().descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: CriticsPickAlbum) {
        Surrogate.serializer().serialize(encoder, Surrogate(
            id = value.id, title = value.title, artist = value.artist,
            link = value.link, description = value.description,
            spotifyUrl = value.spotifyUrl, pubDateMs = value.pubDate?.time,
            albumArt = value.albumArt,
        ))
    }
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): CriticsPickAlbum {
        val s = Surrogate.serializer().deserialize(decoder)
        return CriticsPickAlbum(
            id = s.id, title = s.title, artist = s.artist,
            link = s.link, description = s.description,
            spotifyUrl = s.spotifyUrl, pubDate = s.pubDateMs?.let { Date(it) },
            albumArt = s.albumArt,
        )
    }
}

/**
 * A critics' pick album from the RSS feed.
 * Matches the desktop's album object shape.
 */
data class CriticsPickAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val link: String? = null,
    val description: String = "",
    val spotifyUrl: String? = null,
    val pubDate: Date? = null,
    val albumArt: String? = null,
)
