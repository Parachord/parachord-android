package com.parachord.android.data.metadata

import com.parachord.shared.platform.Log
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Discogs metadata provider.
 *
 * Standalone provider that works without MusicBrainz (uses artist name search).
 * Provides artist biographies and images from the Discogs community database.
 *
 * Priority 15: between Last.fm (10) and Spotify (20).
 * Works without authentication but supports optional personal access token
 * for increased rate limits.
 *
 * Mirrors the desktop app's getDiscogsBio() and getDiscogsArtistImage() functions.
 */
class DiscogsProvider constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) : MetadataProvider {

    companion object {
        private const val TAG = "DiscogsProvider"
        private const val BASE_URL = "https://api.discogs.com"
        private const val USER_AGENT = "Parachord/1.0 (https://parachord.app)"
    }

    override val name = "discogs"
    override val priority = 15

    override suspend fun isAvailable(): Boolean = true

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> = emptyList()
    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> = emptyList()
    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> = emptyList()

    /**
     * Get artist bio and image from Discogs.
     * 1. Search for artist by name
     * 2. Fetch full artist profile (bio + images)
     */
    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Search for the artist
            val artistId = searchArtistId(artistName) ?: return@withContext null

            // Step 2: Fetch full artist profile
            val (bio, imageUrl, discogsUrl) = fetchArtistProfile(artistId)

            if (bio == null && imageUrl == null) return@withContext null

            ArtistInfo(
                name = artistName,
                imageUrl = imageUrl,
                bio = bio,
                bioSource = "discogs",
                bioUrl = discogsUrl,
                provider = name,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Discogs lookup failed for '$artistName'", e)
            null
        }
    }

    /**
     * Search Discogs for an artist by name. Returns the best-matching artist ID.
     * Prefers exact name match, falls back to first result.
     */
    private suspend fun searchArtistId(artistName: String): Long? {
        val encoded = java.net.URLEncoder.encode(artistName, "UTF-8")
        val url = "$BASE_URL/database/search?q=$encoded&type=artist&per_page=5"

        val response = executeRequest(url) ?: return null
        val results = response.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        // Prefer exact name match
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val title = result.optString("title", "")
            if (title.equals(artistName, ignoreCase = true)) {
                return result.optLong("id", 0).takeIf { it > 0 }
            }
        }

        // Fall back to first result
        return results.optJSONObject(0)?.optLong("id", 0)?.takeIf { it > 0 }
    }

    /**
     * Fetch full artist profile from Discogs.
     * Returns (bio, imageUrl, profileUrl).
     */
    private suspend fun fetchArtistProfile(artistId: Long): Triple<String?, String?, String?> {
        val url = "$BASE_URL/artists/$artistId"
        val json = executeRequest(url) ?: return Triple(null, null, null)

        // Bio: clean Discogs markup from profile text
        val rawProfile = json.optString("profile", "").trim()
        val bio = if (rawProfile.isNotBlank()) cleanDiscogsMarkup(rawProfile) else null

        // Image: prefer "primary" type, fall back to first image
        val images = json.optJSONArray("images")
        var imageUrl: String? = null
        if (images != null && images.length() > 0) {
            for (i in 0 until images.length()) {
                val img = images.optJSONObject(i) ?: continue
                val type = img.optString("type", "")
                val uri = img.optString("uri", "").ifBlank { null }
                if (type == "primary" && uri != null) {
                    imageUrl = uri
                    break
                }
            }
            if (imageUrl == null) {
                imageUrl = images.optJSONObject(0)?.optString("uri", "")?.ifBlank { null }
            }
        }

        val profileUrl = json.optString("uri", "").ifBlank { null }

        return Triple(bio, imageUrl, profileUrl)
    }

    /**
     * Execute a GET request to the Discogs API with proper headers.
     */
    private suspend fun executeRequest(url: String): JSONObject? {
        val token = settingsStore.getDiscogsToken()
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", USER_AGENT)

        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Discogs token=$token")
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "Discogs API error: ${response.code} for $url")
                return null
            }
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Discogs request failed: $url", e)
            null
        }
    }

    /**
     * Clean Discogs-specific markup from profile text.
     * Removes [a=Artist], [l=Label], [m=Master], [r=Release], [url=...]...[/url] links.
     */
    private fun cleanDiscogsMarkup(text: String): String =
        text.replace(Regex("""\[([almr])=([^\]]*)\]"""), "$2")
            .replace(Regex("""\[url=[^\]]*](.*?)\[/url]"""), "$1")
            .replace(Regex("""\[/?[a-z]+]"""), "")
            .trim()
}
