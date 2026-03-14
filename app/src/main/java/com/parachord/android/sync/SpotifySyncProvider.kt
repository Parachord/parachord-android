package com.parachord.android.sync

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.*
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.delay
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifySyncProvider @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) {
    companion object {
        private const val TAG = "SpotifySyncProvider"
        const val PROVIDER_ID = "spotify"
        private const val BATCH_SIZE = 50
        private const val PLAYLIST_TRACK_BATCH_SIZE = 100
        private const val MAX_RETRIES = 5
    }

    data class SyncedTrack(
        val entity: TrackEntity,
        val spotifyId: String,
        val addedAt: Long,
    )

    data class SyncedAlbum(
        val entity: AlbumEntity,
        val spotifyId: String,
        val addedAt: Long,
    )

    data class SyncedArtist(
        val entity: ArtistEntity,
        val spotifyId: String,
    )

    data class SyncedPlaylist(
        val entity: PlaylistEntity,
        val spotifyId: String,
        val snapshotId: String?,
        val trackCount: Int,
        val isOwned: Boolean,
    )

    private suspend fun auth(): String = "Bearer ${settingsStore.getSpotifyAccessToken() ?: ""}"

    /**
     * Execute a Spotify API call with automatic token refresh on 401,
     * rate-limit handling on 429, and exponential backoff on server errors.
     */
    private suspend fun <T> withRetry(block: suspend (auth: String) -> T): T {
        var retries = 0
        while (true) {
            try {
                return block(auth())
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    401 -> {
                        if (retries > 0) throw e
                        Log.d(TAG, "Token expired, refreshing...")
                        if (!oAuthManager.refreshSpotifyToken()) throw e
                        retries++
                    }
                    429 -> {
                        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull() ?: 1
                        if (retries >= MAX_RETRIES) throw e
                        Log.d(TAG, "Rate limited, waiting ${retryAfter}s")
                        delay(retryAfter * 1000)
                        retries++
                    }
                    in 500..599 -> {
                        if (retries >= MAX_RETRIES) throw e
                        val backoff = (1L shl retries) * 1000
                        Log.d(TAG, "Server error ${e.code()}, backoff ${backoff}ms")
                        delay(backoff)
                        retries++
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun parseIsoTimestamp(iso: String?): Long {
        if (iso == null) return System.currentTimeMillis()
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // ── Fetch operations ─────────────────────────────────────────

    suspend fun fetchTracks(
        localCount: Int = 0,
        latestExternalId: String? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedTrack>? {
        val probe = withRetry { spotifyApi.getLikedTracks(it, limit = 1) }
        if (probe.total == localCount && probe.items.firstOrNull()?.track?.id == latestExternalId) {
            Log.d(TAG, "Tracks unchanged (count=$localCount), skipping full fetch")
            return null
        }

        val all = mutableListOf<SyncedTrack>()
        var offset = 0
        val total = probe.total
        onProgress(0, total)

        while (offset < total) {
            val page = withRetry { spotifyApi.getLikedTracks(it, limit = BATCH_SIZE, offset = offset) }
            page.items.forEach { saved ->
                val track = saved.track
                all.add(SyncedTrack(
                    entity = TrackEntity(
                        id = "spotify-${track.id}",
                        title = track.name,
                        artist = track.artistName,
                        album = track.album?.name,
                        albumId = track.album?.id?.let { "spotify-$it" },
                        duration = track.durationMs,
                        artworkUrl = track.album?.images?.bestImageUrl(),
                        spotifyUri = "spotify:track:${track.id}",
                        spotifyId = track.id,
                        resolver = "spotify",
                        sourceType = "synced",
                    ),
                    spotifyId = track.id,
                    addedAt = parseIsoTimestamp(saved.addedAt),
                ))
            }
            offset += BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun fetchAlbums(
        localCount: Int = 0,
        latestExternalId: String? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedAlbum>? {
        val probe = withRetry { spotifyApi.getSavedAlbums(it, limit = 1) }
        if (probe.total == localCount && probe.items.firstOrNull()?.album?.id == latestExternalId) {
            return null
        }

        val all = mutableListOf<SyncedAlbum>()
        var offset = 0
        val total = probe.total

        while (offset < total) {
            val page = withRetry { spotifyApi.getSavedAlbums(it, limit = BATCH_SIZE, offset = offset) }
            page.items.forEach { saved ->
                val album = saved.album
                all.add(SyncedAlbum(
                    entity = AlbumEntity(
                        id = "spotify-${album.id}",
                        title = album.name,
                        artist = album.artistName,
                        artworkUrl = album.images.bestImageUrl(),
                        year = album.year,
                        trackCount = album.totalTracks,
                        spotifyId = album.id,
                    ),
                    spotifyId = album.id,
                    addedAt = parseIsoTimestamp(saved.addedAt),
                ))
            }
            offset += BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun fetchArtists(
        localCount: Int = 0,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedArtist>? {
        val all = mutableListOf<SyncedArtist>()
        var after: String? = null
        var total = 0

        do {
            val response = withRetry { spotifyApi.getFollowedArtists(it, after = after) }
            total = response.artists.total
            response.artists.items.forEach { artist ->
                all.add(SyncedArtist(
                    entity = ArtistEntity(
                        id = "spotify-${artist.id}",
                        name = artist.name,
                        imageUrl = artist.images.bestImageUrl(),
                        spotifyId = artist.id,
                        genres = artist.genres.joinToString(","),
                    ),
                    spotifyId = artist.id,
                ))
            }
            after = response.artists.cursors?.after
            onProgress(all.size, total)
        } while (after != null)

        if (all.size == localCount) return null
        return all
    }

    suspend fun fetchPlaylists(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedPlaylist> {
        val currentUser = withRetry { spotifyApi.getCurrentUser(it) }
        val all = mutableListOf<SyncedPlaylist>()
        val seen = mutableSetOf<String>()
        var offset = 0

        val probe = withRetry { spotifyApi.getUserPlaylists(it, limit = 1) }
        val total = probe.total

        while (offset < total) {
            val page = withRetry { spotifyApi.getUserPlaylists(it, limit = BATCH_SIZE, offset = offset) }
            page.items.forEach { playlist ->
                if (seen.add(playlist.id)) {
                    all.add(SyncedPlaylist(
                        entity = PlaylistEntity(
                            id = "spotify-${playlist.id}",
                            name = playlist.name,
                            description = playlist.description,
                            artworkUrl = playlist.images.bestImageUrl(),
                            trackCount = playlist.tracks?.total ?: 0,
                            spotifyId = playlist.id,
                            snapshotId = playlist.snapshotId,
                        ),
                        spotifyId = playlist.id,
                        snapshotId = playlist.snapshotId,
                        trackCount = playlist.tracks?.total ?: 0,
                        isOwned = playlist.owner?.id == currentUser.id,
                    ))
                }
            }
            offset += BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun fetchPlaylistTracks(
        spotifyPlaylistId: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PlaylistTrackEntity> {
        val all = mutableListOf<PlaylistTrackEntity>()
        var offset = 0
        val localPlaylistId = "spotify-$spotifyPlaylistId"

        val probe = withRetry { spotifyApi.getPlaylistTracks(it, spotifyPlaylistId, limit = 1) }
        val total = probe.total

        while (offset < total) {
            val page = withRetry {
                spotifyApi.getPlaylistTracks(it, spotifyPlaylistId, limit = PLAYLIST_TRACK_BATCH_SIZE, offset = offset)
            }
            page.items.forEach { item ->
                val track = item.track ?: return@forEach
                all.add(PlaylistTrackEntity(
                    playlistId = localPlaylistId,
                    position = all.size,
                    trackTitle = track.name,
                    trackArtist = track.artistName,
                    trackAlbum = track.album?.name,
                    trackDuration = track.durationMs,
                    trackArtworkUrl = track.album?.images?.bestImageUrl(),
                    trackResolver = "spotify",
                    trackSpotifyUri = "spotify:track:${track.id}",
                    addedAt = parseIsoTimestamp(item.addedAt),
                ))
            }
            offset += PLAYLIST_TRACK_BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun getPlaylistSnapshotId(spotifyPlaylistId: String): String? {
        return try {
            val playlist = withRetry {
                spotifyApi.getPlaylist(it, spotifyPlaylistId, fields = "snapshot_id")
            }
            playlist.snapshotId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get snapshot for $spotifyPlaylistId", e)
            null
        }
    }

    // ── Write operations ─────────────────────────────────────────

    suspend fun saveTracks(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.saveTracks(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun removeTracks(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.removeTracks(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun saveAlbums(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.saveAlbums(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun removeAlbums(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.removeAlbums(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun followArtists(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.followArtists(it, body = SpIdsRequest(batch)) }
        }
    }

    suspend fun unfollowArtists(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.unfollowArtists(it, body = SpIdsRequest(batch)) }
        }
    }

    suspend fun createPlaylistOnSpotify(name: String, description: String? = null): SpPlaylistFull {
        val user = withRetry { spotifyApi.getCurrentUser(it) }
        return withRetry {
            spotifyApi.createPlaylist(it, user.id, SpCreatePlaylistRequest(name, description))
        }
    }

    suspend fun replacePlaylistTracks(spotifyPlaylistId: String, spotifyUris: List<String>): String? {
        if (spotifyUris.isEmpty()) {
            withRetry { spotifyApi.replacePlaylistTracks(it, spotifyPlaylistId, SpUrisRequest(emptyList())) }
            return null
        }

        val chunks = spotifyUris.chunked(PLAYLIST_TRACK_BATCH_SIZE)
        var snapshotId: String? = null

        chunks.forEachIndexed { index, chunk ->
            val response = if (index == 0) {
                withRetry { spotifyApi.replacePlaylistTracks(it, spotifyPlaylistId, SpUrisRequest(chunk)) }
            } else {
                withRetry { spotifyApi.addPlaylistTracks(it, spotifyPlaylistId, SpUrisRequest(chunk)) }
            }
            if (response.isSuccessful) {
                snapshotId = response.body()?.snapshotId
            }
        }

        return snapshotId
    }

    suspend fun updatePlaylistDetails(spotifyPlaylistId: String, name: String?, description: String?) {
        withRetry {
            spotifyApi.updatePlaylistDetails(it, spotifyPlaylistId, SpUpdatePlaylistRequest(name, description))
        }
    }

    suspend fun deletePlaylist(spotifyPlaylistId: String) {
        withRetry { spotifyApi.unfollowPlaylist(it, spotifyPlaylistId) }
    }
}
