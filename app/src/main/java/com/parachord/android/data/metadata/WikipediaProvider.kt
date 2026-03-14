package com.parachord.android.data.metadata

import android.util.Log
import com.parachord.android.data.api.MusicBrainzApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wikipedia metadata provider.
 *
 * Uses the same 3-step chain as the desktop app:
 * 1. MusicBrainz artist lookup → extract Wikidata relation URL
 * 2. Wikidata API → get English Wikipedia article title
 * 3. Wikipedia API → get article extract (bio) + thumbnail image
 *
 * Priority 5: between MusicBrainz (0) and Last.fm (10).
 * Wikipedia bios are encyclopedia-style and longer than Last.fm's.
 * All APIs are public, no auth required.
 */
@Singleton
class WikipediaProvider @Inject constructor(
    private val musicBrainzApi: MusicBrainzApi,
    private val okHttpClient: OkHttpClient,
) : MetadataProvider {

    companion object {
        private const val TAG = "WikipediaProvider"
    }

    override val name = "wikipedia"
    override val priority = 5

    override suspend fun isAvailable(): Boolean = true

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> = emptyList()
    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> = emptyList()
    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> = emptyList()

    /**
     * Get artist bio and image from Wikipedia via MusicBrainz → Wikidata → Wikipedia chain.
     */
    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Resolve artist MBID
            val mbArtist = musicBrainzApi.searchArtists(artistName, limit = 1)
                .artists.firstOrNull() ?: return@withContext null
            val mbid = mbArtist.id

            // Step 2: Get artist relations to find Wikidata link
            val detail = musicBrainzApi.getArtist(mbid)
            val wikidataUrl = detail.relations
                .firstOrNull { it.type == "wikidata" }
                ?.url?.resource ?: return@withContext null

            val wikidataId = wikidataUrl.substringAfterLast("/")
            if (wikidataId.isBlank()) return@withContext null

            // Step 3: Get Wikipedia article title from Wikidata
            val wikiTitle = getWikiTitleFromWikidata(wikidataId) ?: return@withContext null

            // Step 4: Fetch bio and image from Wikipedia
            val bio = getWikipediaBio(wikiTitle)
            val (imageUrl, pageUrl) = getWikipediaImageAndUrl(wikiTitle)

            if (bio == null && imageUrl == null) return@withContext null

            ArtistInfo(
                name = artistName,
                mbid = mbid,
                imageUrl = imageUrl,
                bio = bio,
                bioSource = "wikipedia",
                bioUrl = pageUrl,
                provider = name,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia lookup failed for '$artistName'", e)
            null
        }
    }

    /**
     * Query Wikidata to get the English Wikipedia article title for a Wikidata entity.
     */
    private fun getWikiTitleFromWikidata(wikidataId: String): String? {
        val url = "https://www.wikidata.org/w/api.php" +
            "?action=wbgetentities&ids=$wikidataId&props=sitelinks&sitefilter=enwiki&format=json"
        val request = Request.Builder().url(url).get().build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful || body == null) return null

        val json = JSONObject(body)
        val entities = json.optJSONObject("entities") ?: return null
        val entity = entities.optJSONObject(wikidataId) ?: return null
        val sitelinks = entity.optJSONObject("sitelinks") ?: return null
        val enwiki = sitelinks.optJSONObject("enwiki") ?: return null
        return enwiki.optString("title", "").ifBlank { null }
    }

    /**
     * Fetch the introductory extract of a Wikipedia article as plain text.
     */
    private fun getWikipediaBio(wikiTitle: String): String? {
        val encodedTitle = java.net.URLEncoder.encode(wikiTitle, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php" +
            "?action=query&titles=$encodedTitle&prop=extracts&exintro=1&explaintext=1&format=json"
        val request = Request.Builder().url(url).get().build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful || body == null) return null

        val json = JSONObject(body)
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().firstOrNull()?.let { pages.optJSONObject(it) }
            ?: return null

        val extract = page.optString("extract", "").trim()
        return extract.ifBlank { null }
    }

    /**
     * Fetch the thumbnail image URL and page URL from the Wikipedia REST API.
     */
    private fun getWikipediaImageAndUrl(wikiTitle: String): Pair<String?, String?> {
        val encodedTitle = java.net.URLEncoder.encode(wikiTitle, "UTF-8")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
        val request = Request.Builder().url(url).get().build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return Pair(null, null)

            val json = JSONObject(body)
            val thumbnail = json.optJSONObject("thumbnail")?.optString("source")
                ?.ifBlank { null }
            val originalImage = json.optJSONObject("originalimage")?.optString("source")
                ?.ifBlank { null }
            val pageUrl = json.optJSONObject("content_urls")
                ?.optJSONObject("desktop")
                ?.optString("page")
                ?.ifBlank { null }

            Pair(originalImage ?: thumbnail, pageUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Wikipedia image for '$wikiTitle'", e)
            Pair(null, null)
        }
    }
}
