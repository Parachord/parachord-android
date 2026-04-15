package com.parachord.android.data.api

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * ListenBrainz API client for fetching listening history and stats.
 * Uses OkHttp directly (not Retrofit) since it needs token-based auth header.
 *
 * API docs: https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
 */
class ListenBrainzApi constructor(
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
     * Fetch the list of users this user follows on ListenBrainz.
     * No auth required.
     */
    suspend fun getUserFollowing(username: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/$username/following"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext emptyList()

            val json = JSONObject(body)
            val following = json.optJSONArray("following") ?: return@withContext emptyList()
            (0 until following.length()).map { following.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch following for $username", e)
            emptyList()
        }
    }

    /**
     * Follow a user on ListenBrainz. Requires auth token.
     * POST /1/user/{user_name}/follow
     */
    suspend fun followUser(username: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/$username/follow"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .addHeader("Authorization", "Token $token")
            .build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Failed to follow $username", e)
            false
        }
    }

    /**
     * Unfollow a user on ListenBrainz. Requires auth token.
     * POST /1/user/{user_name}/unfollow
     */
    suspend fun unfollowUser(username: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/$username/unfollow"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .addHeader("Authorization", "Token $token")
            .build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unfollow $username", e)
            false
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
        val encoded = Uri.encode(username)
        val url = "$BASE_URL/1/user/$encoded/listens?count=$count"
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
        val encoded = Uri.encode(username)
        val url = "$BASE_URL/1/stats/user/$encoded/artists?range=$range&count=$count"
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
        val encoded = Uri.encode(username)
        val url = "$BASE_URL/1/stats/user/$encoded/recordings?range=$range&count=$count"
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
     * Fetch playlists "created for" a user by ListenBrainz (Weekly Jams, Weekly Exploration, etc.).
     * Returns raw playlist metadata (title, identifier, date, annotation).
     * No auth required.
     */
    suspend fun getCreatedForPlaylists(
        username: String,
        count: Int = 100,
    ): List<LbCreatedForPlaylist> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/1/user/${java.net.URLEncoder.encode(username, "UTF-8")}/playlists/createdfor?count=$count"
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = executeWithRetry(request)
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext emptyList()

            val json = JSONObject(body)
            val playlists = json.optJSONArray("playlists") ?: return@withContext emptyList()

            (0 until playlists.length()).mapNotNull { i ->
                try {
                    val wrapper = playlists.getJSONObject(i)
                    val playlist = wrapper.optJSONObject("playlist") ?: return@mapNotNull null
                    val identifier = playlist.optString("identifier", "")
                    val mbid = identifier.substringAfterLast("/").ifBlank { return@mapNotNull null }
                    LbCreatedForPlaylist(
                        id = mbid,
                        title = playlist.optString("title", ""),
                        date = playlist.optString("date", ""),
                        annotation = playlist.optString("annotation", ""),
                    )
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch created-for playlists for $username", e)
            emptyList()
        }
    }

    /**
     * Fetch tracks from a specific ListenBrainz playlist by MBID.
     * Returns rich track data including album art from Cover Art Archive.
     */
    suspend fun getPlaylistTracksRich(playlistMbid: String): List<LbPlaylistTrack> =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/1/playlist/$playlistMbid"
            val request = Request.Builder().url(url).get().build()
            return@withContext try {
                val response = executeWithRetry(request)
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
                        val addlMeta = ext?.optJSONObject("additional_metadata")
                        val caaReleaseMbid = addlMeta?.optString("caa_release_mbid", "")
                            ?.ifBlank { null }
                        val albumArt = caaReleaseMbid?.let {
                            "https://coverartarchive.org/release/$it/front-250"
                        }
                        val recordingMbid = track.optJSONArray("identifier")
                            ?.optString(0, "")
                            ?.substringAfterLast("/")
                            ?.ifBlank { null }
                        LbPlaylistTrack(
                            id = recordingMbid ?: "lb-track-$playlistMbid-$i",
                            title = track.optString("title", "Unknown Track"),
                            artist = track.optString("creator", "Unknown Artist"),
                            album = ext?.optString("release_name", "")?.ifBlank { null }
                                ?: addlMeta?.optString("release_name", "")?.ifBlank { null },
                            albumArt = albumArt,
                            durationMs = track.optLong("duration", 0).takeIf { it > 0 },
                            mbid = recordingMbid,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse playlist track at index $i", e)
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playlist $playlistMbid", e)
                emptyList()
            }
        }

    /**
     * Execute a request with retry logic for 429/503 responses (matching desktop's fetchListenBrainz).
     */
    private fun executeWithRetry(request: Request, maxRetries: Int = 3): okhttp3.Response {
        for (attempt in 0 until maxRetries) {
            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) return response
                if (response.code == 429 || response.code == 503) {
                    response.close()
                    val delay = Math.pow(2.0, attempt.toDouble()).toLong() * 1500
                    Thread.sleep(delay)
                    continue
                }
                return response
            } catch (e: java.io.IOException) {
                if (attempt < maxRetries - 1) {
                    val delay = Math.pow(2.0, attempt.toDouble()).toLong() * 1500
                    Thread.sleep(delay)
                } else throw e
            }
        }
        return okHttpClient.newCall(request).execute()
    }

    /**
     * Look up a recording via the ListenBrainz MBID Mapper.
     * Returns the artist credit MBIDs in ~4ms (vs 500ms+ for MusicBrainz search).
     * No auth required. No documented strict rate limits.
     *
     * https://mapper.listenbrainz.org/mapping/lookup
     */
    suspend fun mbidMapperLookup(
        artistName: String,
        recordingName: String,
    ): MbidMapperResult? = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("mapper.listenbrainz.org")
            .addPathSegments("mapping/lookup")
            .addQueryParameter("artist_credit_name", artistName)
            .addQueryParameter("recording_name", recordingName)
            .build()
        val request = Request.Builder().url(url).get().build()
        return@withContext try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext null

            val json = JSONObject(body)
            // The mapper returns an empty object {} when there's no match
            if (!json.has("artist_credit_mbids")) return@withContext null

            val artistMbids = json.optJSONArray("artist_credit_mbids")
            val firstArtistMbid = artistMbids?.optString(0, "")?.ifBlank { null }

            MbidMapperResult(
                artistMbid = firstArtistMbid,
                artistCreditName = json.optString("artist_credit_name", "").ifBlank { null },
                recordingName = json.optString("recording_name", "").ifBlank { null },
                recordingMbid = json.optString("recording_mbid", "").ifBlank { null },
                releaseName = json.optString("release_name", "").ifBlank { null },
                releaseMbid = json.optString("release_mbid", "").ifBlank { null },
                confidence = json.optDouble("confidence", 0.0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "MBID mapper lookup failed for '$artistName' - '$recordingName'", e)
            null
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
        val encoded = Uri.encode(username)
        val url = "$BASE_URL/1/stats/user/$encoded/releases?range=$range&count=$count"
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

/** A playlist "created for" a user by ListenBrainz (Weekly Jams, Weekly Exploration, etc.). */
data class LbCreatedForPlaylist(
    val id: String,      // Playlist MBID
    val title: String,
    val date: String,    // ISO date
    val annotation: String = "",
)

/** A track from a ListenBrainz playlist with album art info. */
data class LbPlaylistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArt: String? = null,
    val durationMs: Long? = null,
    val mbid: String? = null,
)

/** Result from the ListenBrainz MBID Mapper lookup. */
data class MbidMapperResult(
    val artistMbid: String?,
    val artistCreditName: String?,
    val recordingName: String?,
    val recordingMbid: String?,
    val releaseName: String?,
    val releaseMbid: String?,
    val confidence: Double = 0.0,
)
