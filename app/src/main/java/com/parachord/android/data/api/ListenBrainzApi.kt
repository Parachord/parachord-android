package com.parachord.android.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ListenBrainz API client for fetching listening history and stats.
 * Uses OkHttp directly (not Retrofit) since it needs token-based auth header.
 *
 * API docs: https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
 */
@Singleton
class ListenBrainzApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ListenBrainzApi"
        private const val BASE_URL = "https://api.listenbrainz.org"
    }

    /**
     * Validate that a ListenBrainz user exists by checking their listen count.
     * No auth required.
     */
    suspend fun validateUser(username: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/$username/listen-count"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate user $username", e)
            false
        }
    }

    /**
     * Validate a ListenBrainz user token and return the associated username.
     * Calls GET /1/validate-token with Authorization header.
     * Returns the username if valid, null if invalid or error.
     */
    suspend fun validateToken(token: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/validate-token"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Token $token")
            .build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "Token validation failed: ${response.code}")
                return@withContext null
            }
            val json = JSONObject(body)
            if (json.optBoolean("valid", false)) {
                json.optString("user_name", "").ifBlank { null }
            } else {
                Log.w(TAG, "Token is not valid")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate token", e)
            null
        }
    }

    /**
     * Fetch recent listens for a user.
     * No auth required for public profiles.
     */
    suspend fun getRecentListens(
        username: String,
        token: String? = null,
        count: Int = 50,
    ): List<LbListen> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/$username/listens?count=$count"
        val requestBuilder = Request.Builder().url(url).get()
        if (token != null) {
            requestBuilder.addHeader("Authorization", "Token $token")
        }

        return@withContext try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "API error ${response.code}: $body")
                return@withContext emptyList()
            }

            val json = JSONObject(body)
            val payload = json.getJSONObject("payload")
            val listens = payload.getJSONArray("listens")

            (0 until listens.length()).mapNotNull { i ->
                try {
                    val listen = listens.getJSONObject(i)
                    val metadata = listen.getJSONObject("track_metadata")
                    LbListen(
                        artistName = metadata.optString("artist_name", ""),
                        trackName = metadata.optString("track_name", ""),
                        releaseName = metadata.optString("release_name", "").ifBlank { null },
                        listenedAt = listen.optLong("listened_at", 0),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse listen at index $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch listens for $username", e)
            emptyList()
        }
    }

    /**
     * Fetch user's top artists for a given time range.
     * Range values: week, month, quarter, half_yearly, year, all_time
     */
    suspend fun getUserTopArtists(
        username: String,
        range: String = "month",
        count: Int = 50,
    ): List<LbArtistStat> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/stats/user/$username/artists?range=$range&count=$count"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext emptyList()

            val json = JSONObject(body)
            val payload = json.getJSONObject("payload")
            val artists = payload.getJSONArray("artists")

            (0 until artists.length()).mapNotNull { i ->
                try {
                    val artist = artists.getJSONObject(i)
                    LbArtistStat(
                        name = artist.optString("artist_name", ""),
                        listenCount = artist.optInt("listen_count", 0),
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top artists for $username", e)
            emptyList()
        }
    }

    /**
     * Fetch user's top recordings (tracks) for a given time range.
     */
    suspend fun getUserTopRecordings(
        username: String,
        range: String = "month",
        count: Int = 50,
    ): List<LbRecordingStat> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/stats/user/$username/recordings?range=$range&count=$count"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext emptyList()

            val json = JSONObject(body)
            val payload = json.getJSONObject("payload")
            val recordings = payload.getJSONArray("recordings")

            (0 until recordings.length()).mapNotNull { i ->
                try {
                    val rec = recordings.getJSONObject(i)
                    LbRecordingStat(
                        trackName = rec.optString("track_name", ""),
                        artistName = rec.optString("artist_name", ""),
                        releaseName = rec.optString("release_name", ""),
                        listenCount = rec.optInt("listen_count", 0),
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top recordings for $username", e)
            emptyList()
        }
    }

    /**
     * Fetch user's recommendation playlists from ListenBrainz.
     * These are "createdfor" playlists where LB generates personalized recommendations.
     * Returns recommended tracks extracted from the most recent recommendation playlist.
     */
    suspend fun getRecommendationPlaylistTracks(
        username: String,
    ): List<LbRecommendedTrack> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/$username/playlists/createdfor"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "Failed to fetch recommendation playlists: ${response.code}")
                return@withContext emptyList()
            }

            val json = JSONObject(body)
            val playlists = json.optJSONArray("playlists") ?: return@withContext emptyList()

            // Find a recommendation playlist (title contains "Recommended" or is a weekly exploration)
            var recPlaylistMbid: String? = null
            for (i in 0 until playlists.length()) {
                val playlist = playlists.getJSONObject(i).optJSONObject("playlist") ?: continue
                val title = playlist.optString("title", "")
                if (title.contains("Recommended", ignoreCase = true) ||
                    title.contains("Weekly Exploration", ignoreCase = true) ||
                    title.contains("Weekly Jams", ignoreCase = true)
                ) {
                    recPlaylistMbid = playlist.optString("identifier", "")
                        .substringAfterLast("/")
                    break
                }
            }

            if (recPlaylistMbid.isNullOrBlank()) {
                Log.d(TAG, "No recommendation playlists found for $username")
                return@withContext emptyList()
            }

            // Fetch the actual playlist tracks
            fetchPlaylistTracks(recPlaylistMbid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recommendation playlists for $username", e)
            emptyList()
        }
    }

    /**
     * Fetch tracks from a specific ListenBrainz playlist by MBID.
     */
    private suspend fun fetchPlaylistTracks(playlistMbid: String): List<LbRecommendedTrack> =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/1/playlist/$playlistMbid"
            val request = Request.Builder().url(url).get().build()
            return@withContext try {
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) return@withContext emptyList()

                val json = JSONObject(body)
                val playlist = json.optJSONObject("playlist") ?: return@withContext emptyList()
                val tracks = playlist.optJSONArray("track") ?: return@withContext emptyList()

                (0 until tracks.length()).mapNotNull { i ->
                    try {
                        val track = tracks.getJSONObject(i)
                        val ext = track.optJSONObject("extension")
                            ?.optJSONObject("https://musicbrainz.org/doc/jspf#track")
                        LbRecommendedTrack(
                            title = track.optString("title", ""),
                            artist = track.optString("creator", ""),
                            album = ext?.optString("release_name") ?: "",
                        )
                    } catch (e: Exception) { null }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playlist $playlistMbid", e)
                emptyList()
            }
        }

    /**
     * Fetch user's top releases (albums) for a given time range.
     */
    suspend fun getUserTopReleases(
        username: String,
        range: String = "month",
        count: Int = 50,
    ): List<LbReleaseStat> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/stats/user/$username/releases?range=$range&count=$count"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext emptyList()

            val json = JSONObject(body)
            val payload = json.getJSONObject("payload")
            val releases = payload.getJSONArray("releases")

            (0 until releases.length()).mapNotNull { i ->
                try {
                    val rel = releases.getJSONObject(i)
                    LbReleaseStat(
                        releaseName = rel.optString("release_name", ""),
                        artistName = rel.optString("artist_name", ""),
                        listenCount = rel.optInt("listen_count", 0),
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top releases for $username", e)
            emptyList()
        }
    }
}

/** A single listen from the ListenBrainz API. */
data class LbListen(
    val artistName: String,
    val trackName: String,
    val releaseName: String? = null,
    val listenedAt: Long = 0,
)

/** Artist stat from ListenBrainz stats API. */
data class LbArtistStat(
    val name: String,
    val listenCount: Int = 0,
)

/** Recording (track) stat from ListenBrainz stats API. */
data class LbRecordingStat(
    val trackName: String,
    val artistName: String,
    val releaseName: String? = null,
    val listenCount: Int = 0,
)

/** Release (album) stat from ListenBrainz stats API. */
data class LbReleaseStat(
    val releaseName: String,
    val artistName: String,
    val listenCount: Int = 0,
)

/** A recommended track from a ListenBrainz recommendation playlist. */
data class LbRecommendedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
)
