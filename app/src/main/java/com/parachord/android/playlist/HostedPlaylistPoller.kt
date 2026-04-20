package com.parachord.android.playlist

import android.util.Log
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Re-fetches a hosted XSPF playlist and reimports its tracks when the source
 * content has changed. The XSPF is canonical — any local edits on an
 * in-lockstep hosted playlist get overwritten on the next tick.
 *
 * Polling is driven by [HostedPlaylistScheduler] (foreground every 5 min) and
 * [HostedPlaylistWorker] (background via WorkManager, 15-min floor).
 *
 * Errors are logged and swallowed — the next tick retries. This matches
 * desktop's pollHostedPlaylists (app.js L32167+) where a failed fetch is
 * non-fatal.
 */
class HostedPlaylistPoller constructor(
    private val httpClient: OkHttpClient,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val imageEnrichmentService: ImageEnrichmentService,
) {
    companion object {
        private const val TAG = "HostedPlaylistPoller"
    }

    /**
     * Poll every hosted playlist currently in the DB. Safe to call concurrently
     * with itself — each playlist is fetched and updated in isolation.
     */
    suspend fun pollAll() {
        val hosted = playlistDao.getHosted()
        if (hosted.isEmpty()) return
        Log.d(TAG, "Polling ${hosted.size} hosted playlist(s)")
        for (playlist in hosted) {
            poll(playlist)
        }
    }

    /**
     * Poll a single hosted playlist. Returns true if the content changed and
     * tracks were replaced; false if unchanged, if the fetch failed, or if
     * the playlist isn't actually hosted.
     */
    suspend fun poll(playlist: PlaylistEntity): Boolean = withContext(Dispatchers.IO) {
        val url = playlist.sourceUrl ?: return@withContext false
        try {
            // Re-run SSRF guard every poll — a DNS record change since import
            // could redirect the URL to a private address. security: H10
            validateXspfUrl(url)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Skipping hosted playlist '${playlist.name}' — ${e.message}")
            return@withContext false
        }

        val content = try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Hosted playlist '${playlist.name}' fetch failed: HTTP ${response.code}")
                response.close()
                return@withContext false
            }
            response.body?.string().also { response.close() }
                ?: run {
                    Log.w(TAG, "Hosted playlist '${playlist.name}' returned empty body")
                    return@withContext false
                }
        } catch (e: Exception) {
            Log.w(TAG, "Hosted playlist '${playlist.name}' fetch error: ${e.message}")
            return@withContext false
        }

        val hash = sha256Hex(content)
        if (hash == playlist.sourceContentHash) {
            return@withContext false
        }

        if (!content.contains("<playlist") || !content.contains("</playlist>")) {
            Log.w(TAG, "Hosted playlist '${playlist.name}' body is not valid XSPF")
            return@withContext false
        }

        val parsed = try {
            XspfParser.parse(content)
        } catch (e: Exception) {
            Log.w(TAG, "Hosted playlist '${playlist.name}' parse failed: ${e.message}")
            return@withContext false
        }

        val now = System.currentTimeMillis()
        val trackRows = parsed.tracks.mapIndexed { index, track ->
            PlaylistTrackEntity(
                playlistId = playlist.id,
                position = index,
                trackTitle = track.title,
                trackArtist = track.artist,
                trackAlbum = track.album,
                trackDuration = track.duration,
                trackArtworkUrl = track.artworkUrl,
                trackSourceUrl = track.sourceUrl,
                trackResolver = track.resolver,
                trackSpotifyUri = track.spotifyUri,
                trackSoundcloudId = track.soundcloudId,
                trackSpotifyId = track.spotifyId,
                trackAppleMusicId = track.appleMusicId,
                addedAt = now,
            )
        }

        playlistTrackDao.deleteByPlaylistId(playlist.id)
        playlistTrackDao.insertAll(trackRows)
        // Updates trackCount + sourceContentHash + lastModified + locallyModified=1.
        // The locallyModified flag triggers the next SyncEngine push to
        // overwrite Spotify with the XSPF content.
        playlistDao.updateHostedSnapshot(
            playlistId = playlist.id,
            contentHash = hash,
            trackCount = trackRows.size,
            lastModified = now,
        )
        // Content changed → the prior mosaic is now stale (it was built
        // from the old tracklist). Clear `artworkUrl` so the enrichment
        // service's cache-by-URL check forces a fresh build, then
        // regenerate. `enrichPlaylistArt` rewrites the mosaic file at
        // `playlist_mosaics/{id}.jpg` and stores the new file:// URL.
        playlistDao.clearArtworkById(playlist.id)
        try {
            // Pass the first 8 chars of the content hash as the cache-bust
            // token — ensures Coil treats the rewritten mosaic file
            // (same path) as a new URL and reloads instead of serving a
            // stale cached bitmap.
            imageEnrichmentService.enrichPlaylistArt(
                playlistId = playlist.id,
                cacheBustToken = hash.take(8),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Mosaic re-enrichment failed for '${playlist.name}': ${e.message}")
        }
        Log.d(TAG, "Hosted playlist '${playlist.name}' updated (${trackRows.size} tracks)")
        true
    }
}
