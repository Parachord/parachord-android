package com.parachord.android.ai

import com.parachord.android.data.metadata.MetadataService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enriches chat cards with artwork URLs by looking up metadata from providers.
 * Results are cached in-memory to avoid redundant API calls for cards that
 * appear multiple times or when scrolling.
 */
class ChatCardEnricher constructor(
    private val metadataService: MetadataService,
) {
    /** Cache of "type:key" → artworkUrl. Null value means "looked up, nothing found." */
    private val cache = mutableMapOf<String, String?>()
    private val mutex = Mutex()

    /**
     * Get artwork URL for a track card. Searches metadata providers.
     * Returns cached result if available.
     */
    suspend fun getTrackArtwork(title: String, artist: String, album: String): String? {
        val key = "track:${artist.lowercase()}|${title.lowercase()}"
        return getOrFetch(key) {
            // Try searching by "artist title" to find artwork
            val results = metadataService.searchTracks("$artist $title", limit = 3)
            // Find best match (exact or close title+artist)
            val match = results.firstOrNull { r ->
                r.title.equals(title, ignoreCase = true) && r.artist.equals(artist, ignoreCase = true)
            } ?: results.firstOrNull { r ->
                r.title.equals(title, ignoreCase = true)
            } ?: results.firstOrNull()
            match?.artworkUrl
        }
    }

    /**
     * Get artwork URL for an album card. Searches metadata providers.
     */
    suspend fun getAlbumArtwork(albumTitle: String, artist: String): String? {
        val key = "album:${artist.lowercase()}|${albumTitle.lowercase()}"
        return getOrFetch(key) {
            val results = metadataService.searchAlbums("$artist $albumTitle", limit = 3)
            val match = results.firstOrNull { r ->
                r.title.equals(albumTitle, ignoreCase = true) && r.artist.equals(artist, ignoreCase = true)
            } ?: results.firstOrNull { r ->
                r.title.equals(albumTitle, ignoreCase = true)
            } ?: results.firstOrNull()
            match?.artworkUrl
        }
    }

    /**
     * Get image URL for an artist card. Uses artist info lookup.
     */
    suspend fun getArtistImage(artistName: String): String? {
        val key = "artist:${artistName.lowercase()}"
        return getOrFetch(key) {
            val info = metadataService.getArtistInfo(artistName)
            info?.imageUrl
        }
    }

    private suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? {
        mutex.withLock {
            if (key in cache) return cache[key]
        }
        val result = try {
            fetch()
        } catch (_: Exception) {
            null
        }
        mutex.withLock {
            cache[key] = result
        }
        return result
    }
}
