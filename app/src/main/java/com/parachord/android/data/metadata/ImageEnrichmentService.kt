package com.parachord.android.data.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.ArtistDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

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
class ImageEnrichmentService constructor(
    private val context: Context,
    private val metadataService: MetadataService,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-flight deduplication maps
    private val artistFetches = ConcurrentHashMap<String, Deferred<String?>>()
    private val albumFetches = ConcurrentHashMap<String, Deferred<String?>>()
    private val trackFetches = ConcurrentHashMap<String, Deferred<String?>>()
    private val playlistFetches = ConcurrentHashMap<String, Deferred<String?>>()

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

    /**
     * Fetch artwork for a track that has no artworkUrl.
     * Tries album art lookup first (by album+artist), then falls back to search.
     * Persists the result to the tracks table so it's cached for future plays.
     * Returns the artwork URL or null if none found.
     */
    suspend fun enrichTrackArt(trackId: String, trackTitle: String, artistName: String, albumTitle: String?): String? {
        val key = trackId
        val existing = trackFetches[key]
        if (existing != null && existing.isActive) return existing.await()

        val deferred = scope.async {
            try {
                var artworkUrl: String? = null

                // Try album art first (most likely to have artwork)
                if (albumTitle != null) {
                    val detail = metadataService.getAlbumTracks(albumTitle, artistName)
                    artworkUrl = detail?.artworkUrl
                }

                // Fall back to track search
                if (artworkUrl == null) {
                    val results = metadataService.searchTracks("$artistName $trackTitle", limit = 5)
                    artworkUrl = results
                        .firstOrNull {
                            it.title.equals(trackTitle, ignoreCase = true) &&
                                it.artist.equals(artistName, ignoreCase = true)
                        }
                        ?.artworkUrl
                        ?: results.firstOrNull()?.artworkUrl
                }

                if (artworkUrl != null) {
                    trackDao.updateArtworkById(trackId, artworkUrl)
                }
                artworkUrl
            } catch (_: Exception) {
                null
            } finally {
                trackFetches.remove(key)
            }
        }
        trackFetches[key] = deferred
        return deferred.await()
    }

    /**
     * Generate and persist a 2x2 mosaic artwork for a playlist that has no cover.
     * Collects up to 4 unique track artwork URLs from the playlist's tracks,
     * downloads and composites them into a single image, saves it to internal
     * storage, and updates the playlist's artworkUrl in the database.
     *
     * Returns the local file URI or null if not enough artwork is available.
     */
    suspend fun enrichPlaylistArt(playlistId: String): String? {
        val existing = playlistFetches[playlistId]
        if (existing != null && existing.isActive) return existing.await()

        val deferred = scope.async {
            try {
                val tracks = playlistTrackDao.getByPlaylistIdSync(playlistId)

                // Collect up to 4 unique artwork URLs
                val artworkUrls = tracks
                    .mapNotNull { it.trackArtworkUrl }
                    .distinct()
                    .take(4)

                if (artworkUrls.isEmpty()) return@async null

                val resultUrl = if (artworkUrls.size == 1) {
                    // Only one unique artwork — just use it directly
                    artworkUrls.first()
                } else {
                    // Generate 2x2 mosaic (pad to 4 by repeating if needed)
                    val paddedUrls = when (artworkUrls.size) {
                        2 -> listOf(artworkUrls[0], artworkUrls[1], artworkUrls[1], artworkUrls[0])
                        3 -> artworkUrls + listOf(artworkUrls[0])
                        else -> artworkUrls
                    }
                    generateMosaic(playlistId, paddedUrls)
                }

                if (resultUrl != null) {
                    playlistDao.updateArtworkById(playlistId, resultUrl)
                }
                resultUrl
            } catch (_: Exception) {
                null
            } finally {
                playlistFetches.remove(playlistId)
            }
        }
        playlistFetches[playlistId] = deferred
        return deferred.await()
    }

    /**
     * Download 4 images and composite them into a 2x2 mosaic bitmap.
     * Saves the result to internal storage and returns the file URI.
     */
    private suspend fun generateMosaic(playlistId: String, urls: List<String>): String? {
        val mosaicSize = 600 // 600x600 output
        val tileSize = mosaicSize / 2

        val bitmaps = urls.mapNotNull { url ->
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(tileSize)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                (result as? SuccessResult)?.drawable?.let { drawable ->
                    val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, tileSize, tileSize)
                    drawable.draw(canvas)
                    bitmap
                }
            } catch (_: Exception) {
                null
            }
        }

        if (bitmaps.isEmpty()) return null

        // Pad if some downloads failed
        val tiles = when {
            bitmaps.size >= 4 -> bitmaps.take(4)
            bitmaps.size == 1 -> return null // Can't make a mosaic from 1
            else -> {
                // Fill remaining slots by repeating
                val padded = bitmaps.toMutableList()
                while (padded.size < 4) padded.add(bitmaps[padded.size % bitmaps.size])
                padded
            }
        }

        val mosaic = Bitmap.createBitmap(mosaicSize, mosaicSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mosaic)

        // Top-left, Top-right, Bottom-left, Bottom-right
        val positions = listOf(
            0 to 0,
            tileSize to 0,
            0 to tileSize,
            tileSize to tileSize,
        )
        tiles.forEachIndexed { i, tile ->
            canvas.drawBitmap(tile, positions[i].first.toFloat(), positions[i].second.toFloat(), null)
        }

        // Save to internal storage
        val dir = File(context.filesDir, "playlist_mosaics")
        dir.mkdirs()
        val file = File(dir, "$playlistId.jpg")
        FileOutputStream(file).use { out ->
            mosaic.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        // Clean up bitmaps
        tiles.forEach { it.recycle() }
        mosaic.recycle()

        return file.toURI().toString()
    }
}
