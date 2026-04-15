package com.parachord.android.data.repository

import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.api.bestImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class ChartsRepository constructor(
    private val okHttpClient: OkHttpClient,
    private val lastFmApi: LastFmApi,
) {
    companion object {
        private const val TAG = "ChartsRepo"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }

    private val apiKey: String get() = BuildConfig.LASTFM_API_KEY

    private val albumsCache = mutableMapOf<String, Pair<List<ChartAlbum>, Long>>()
    private val songsCache = mutableMapOf<String, Pair<List<ChartSong>, Long>>()

    suspend fun getAppleMusicAlbums(countryCode: String = "us"): List<ChartAlbum> =
        withContext(Dispatchers.IO) {
            val cacheKey = "apple-albums-$countryCode"
            albumsCache[cacheKey]?.let { (data, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext data
            }
            try {
                val url = "https://rss.marketingtools.apple.com/api/v2/$countryCode/music/most-played/50/albums.json"
                val body = fetchJson(url)
                val results = body?.optJSONObject("feed")?.optJSONArray("results") ?: return@withContext emptyList()
                val albums = (0 until results.length()).map { i ->
                    val obj = results.getJSONObject(i)
                    ChartAlbum(
                        id = "apple-album-$countryCode-$i",
                        title = obj.optString("name", ""),
                        artist = obj.optString("artistName", ""),
                        artworkUrl = obj.optString("artworkUrl100", "")
                            .replace("100x100", "300x300")
                            .ifBlank { null },
                        rank = i + 1,
                        genres = obj.optJSONArray("genres")?.let { arr ->
                            (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "") }
                        } ?: emptyList(),
                        url = obj.optString("url", "").ifBlank { null },
                    )
                }
                albumsCache[cacheKey] = albums to System.currentTimeMillis()
                albums
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Apple Music albums ($countryCode)", e)
                albumsCache[cacheKey]?.first ?: emptyList()
            }
        }

    suspend fun getAppleMusicSongs(countryCode: String = "us"): List<ChartSong> =
        withContext(Dispatchers.IO) {
            val cacheKey = "apple-songs-$countryCode"
            songsCache[cacheKey]?.let { (data, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext data
            }
            try {
                val url = "https://rss.marketingtools.apple.com/api/v2/$countryCode/music/most-played/50/songs.json"
                val body = fetchJson(url)
                val results = body?.optJSONObject("feed")?.optJSONArray("results") ?: return@withContext emptyList()
                val songs = (0 until results.length()).map { i ->
                    val obj = results.getJSONObject(i)
                    ChartSong(
                        id = "apple-song-$countryCode-$i",
                        title = obj.optString("name", ""),
                        artist = obj.optString("artistName", ""),
                        artworkUrl = obj.optString("artworkUrl100", "")
                            .replace("100x100", "600x600")
                            .ifBlank { null },
                        rank = i + 1,
                        source = "apple",
                        url = obj.optString("url", "").ifBlank { null },
                    )
                }
                songsCache[cacheKey] = songs to System.currentTimeMillis()
                songs
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Apple Music songs ($countryCode)", e)
                songsCache[cacheKey]?.first ?: emptyList()
            }
        }

    suspend fun getLastfmCharts(countryCode: String? = null): List<ChartSong> =
        withContext(Dispatchers.IO) {
            val cacheKey = "lastfm-${countryCode ?: "global"}"
            songsCache[cacheKey]?.let { (data, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext data
            }
            try {
                val tracks = if (countryCode == null) {
                    val response = lastFmApi.getChartTopTracks(apiKey = apiKey)
                    response.tracks?.track ?: emptyList()
                } else {
                    val countryName = CHARTS_COUNTRIES.find { it.code == countryCode }?.lastfmName
                        ?: return@withContext emptyList()
                    val response = lastFmApi.getGeoTopTracks(country = countryName, apiKey = apiKey)
                    response.tracks?.track ?: emptyList()
                }
                val songs = tracks.mapIndexed { i, t ->
                    ChartSong(
                        id = "lastfm-chart-${countryCode ?: "global"}-$i",
                        title = t.name,
                        artist = t.artist?.name ?: "",
                        artworkUrl = t.image.bestImageUrl(),
                        rank = i + 1,
                        listeners = t.listeners?.toLongOrNull(),
                        playcount = t.playcount?.toLongOrNull(),
                        url = t.url,
                        source = "lastfm",
                        mbid = t.mbid?.takeIf { it.isNotBlank() },
                    )
                }
                // Enrich missing artwork via track.getInfo (concurrent, batched)
                val enriched = enrichArtwork(songs)
                songsCache[cacheKey] = enriched to System.currentTimeMillis()
                enriched
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Last.fm charts ($countryCode)", e)
                songsCache[cacheKey]?.first ?: emptyList()
            }
        }

    private suspend fun enrichArtwork(songs: List<ChartSong>): List<ChartSong> = coroutineScope {
        // Only enrich songs that are missing artwork
        val needsArt = songs.mapIndexedNotNull { i, s -> if (s.artworkUrl == null) i else null }
        if (needsArt.isEmpty()) return@coroutineScope songs

        val result = songs.toMutableList()
        // Fetch in batches of 10 to avoid hammering the API
        needsArt.chunked(10).forEach { batch ->
            val deferred = batch.map { idx ->
                async {
                    idx to fetchTrackArtwork(result[idx].artist, result[idx].title)
                }
            }
            deferred.awaitAll().forEach { (idx, artInfo) ->
                if (artInfo != null) {
                    result[idx] = result[idx].copy(
                        artworkUrl = artInfo.first ?: result[idx].artworkUrl,
                        album = artInfo.second ?: result[idx].album,
                    )
                }
            }
        }
        result
    }

    /** Returns (artworkUrl, albumTitle) or null on failure. */
    private suspend fun fetchTrackArtwork(artist: String, track: String): Pair<String?, String?>? {
        return try {
            val response = lastFmApi.getTrackInfo(track = track, artist = artist, apiKey = apiKey)
            val album = response.track?.album
            val artUrl = album?.image?.bestImageUrl()
            if (artUrl != null || album?.title != null) {
                artUrl to album?.title
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful || body == null) return null
        return JSONObject(body)
    }
}
