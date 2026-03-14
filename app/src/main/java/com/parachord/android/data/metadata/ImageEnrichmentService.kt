package com.parachord.android.data.metadata

import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.ArtistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily enriches artist images and album artwork for collection items
 * that were synced without image URLs.
 *
 * Uses the cascading MetadataService to fetch images from available providers
 * (MusicBrainz/Cover Art Archive, Last.fm, Spotify) and persists them to the
 * Room database so they only need to be fetched once.
 *
 * In-flight requests are deduplicated so that multiple UI items requesting the
 * same artist/album image don't trigger redundant network calls.
 */
@Singleton
class ImageEnrichmentService @Inject constructor(
    private val metadataService: MetadataService,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-flight deduplication maps
    private val artistFetches = ConcurrentHashMap<String, Deferred<String?>>()
    private val albumFetches = ConcurrentHashMap<String, Deferred<String?>>()

    /**
     * Fetch and persist an artist's image if missing.
     * Returns the image URL or null if none found.
     */
    suspend fun enrichArtistImage(artistName: String): String? {
        val key = artistName.lowercase().trim()
        val existing = artistFetches[key]
        if (existing != null && existing.isActive) return existing.await()

        val deferred = scope.async {
            try {
                val info = metadataService.getArtistInfo(artistName)
                val imageUrl = info?.imageUrl
                if (imageUrl != null) {
                    artistDao.updateImageByName(artistName, imageUrl)
                }
                imageUrl
            } catch (_: Exception) {
                null
            } finally {
                artistFetches.remove(key)
            }
        }
        artistFetches[key] = deferred
        return deferred.await()
    }

    /**
     * Fetch and persist an album's artwork if missing.
     * Returns the artwork URL or null if none found.
     */
    suspend fun enrichAlbumArt(albumTitle: String, artistName: String): String? {
        val key = "${albumTitle.lowercase().trim()}|${artistName.lowercase().trim()}"
        val existing = albumFetches[key]
        if (existing != null && existing.isActive) return existing.await()

        val deferred = scope.async {
            try {
                // Try album detail lookup first (gets artwork from tracklist providers)
                val detail = metadataService.getAlbumTracks(albumTitle, artistName)
                var artworkUrl = detail?.artworkUrl

                // Fall back to album search if detail didn't yield artwork
                if (artworkUrl == null) {
                    val searchResults = metadataService.searchAlbums(
                        "$albumTitle $artistName",
                        limit = 5,
                    )
                    artworkUrl = searchResults
                        .firstOrNull {
                            it.title.equals(albumTitle, ignoreCase = true) &&
                                it.artist.equals(artistName, ignoreCase = true)
                        }
                        ?.artworkUrl
                        ?: searchResults.firstOrNull()?.artworkUrl
                }

                if (artworkUrl != null) {
                    albumDao.updateArtworkByTitleAndArtist(albumTitle, artistName, artworkUrl)
                }
                artworkUrl
            } catch (_: Exception) {
                null
            } finally {
                albumFetches.remove(key)
            }
        }
        albumFetches[key] = deferred
        return deferred.await()
    }
}
