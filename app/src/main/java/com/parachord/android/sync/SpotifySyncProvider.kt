package com.parachord.android.sync

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.SpCreatePlaylistRequest
import com.parachord.shared.api.SpIdsRequest
import com.parachord.shared.api.SpSnapshotResponse
import com.parachord.shared.api.SpUpdatePlaylistRequest
import com.parachord.shared.api.SpUrisRequest
import com.parachord.shared.api.bestImageUrl
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import kotlinx.coroutines.delay
import java.time.Instant

// Re-exported for source compatibility while the shared sync package settles.
// Kotlin permits typealiases at file scope and inside object declarations, but
// not inside a class body — companion-object aliases were tried first and
// failed to resolve via `SpotifySyncProvider.SyncedX`. Top-level aliases are
// the working fallback; call sites that previously used the qualified
// `SpotifySyncProvider.SyncedX` form were updated to the bare name in the
// same commit. Both forms ultimately resolve to the same shared types in
// `com.parachord.shared.sync`.
typealias SyncedTrack = com.parachord.shared.sync.SyncedTrack
typealias SyncedAlbum = com.parachord.shared.sync.SyncedAlbum
typealias SyncedArtist = com.parachord.shared.sync.SyncedArtist
typealias SyncedPlaylist = com.parachord.shared.sync.SyncedPlaylist

class SpotifySyncProvider constructor(
    private val spotifyClient: SpotifyClient,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) : SyncProvider {
    companion object {
        private const val TAG = "SpotifySyncProvider"
        const val PROVIDER_ID = "spotify"
        private const val BATCH_SIZE = 50
        private const val PLAYLIST_TRACK_BATCH_SIZE = 100
        private const val MAX_RETRIES = 5
    }

    // ── SyncProvider conformance ─────────────────────────────────────
    // Capability declaration for the multi-provider SyncEngine. SyncEngine
    // routes on `features`, never on `id` — so adding Apple Music or Tidal
    // later doesn't require any change here. See SyncProvider.kt for the
    // semantics of each flag.
    override val id: String = PROVIDER_ID
    override val displayName: String = "Spotify"
    override val features = ProviderFeatures(
        snapshots = SnapshotKind.Opaque,
        supportsFollow = true,
        supportsPlaylistDelete = true,
        supportsPlaylistRename = true,
        supportsTrackReplace = true,
    )

    private var cachedMarket: String? = null

    /** Fetch the user's country code from their Spotify profile for market-aware API calls. */
    suspend fun getMarket(): String {
        cachedMarket?.let { return it }
        val user = withRetry { spotifyClient.getCurrentUser() }
        val market = user.country ?: "US"
        cachedMarket = market
        return market
    }

    /**
     * Execute a Spotify API call with reauth-friendly error mapping.
     *
     * Phase 9E.1.8: 401-driven token refresh + retry now happens transparently
     * via the global [OAuthRefreshPlugin] on `api.spotify.com`. After two strikes
     * the plugin throws [com.parachord.shared.api.auth.ReauthRequiredException]
     * which we map to the same user-facing "reconnect Spotify" error this
     * provider emitted before the cutover.
     *
     * **Regression note:** the old `withRetry` also handled 429 (rate-limited
     * with `Retry-After`) and 5xx (exponential backoff) by inspecting the
     * Retrofit `HttpException`. The shared Ktor client decodes typed responses
     * directly and doesn't expose the status on those paths, so 429 / 5xx now
     * surface as serialization / IOException to the caller and the next sync
     * cycle picks up the work. Sufficient for the cutover; if Spotify
     * rate-limits in practice we'll add a typed `SpHttpException` mid-tier.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: com.parachord.shared.api.auth.ReauthRequiredException) {
            throw IllegalStateException("Spotify session expired. Reconnect Spotify in Settings.")
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

    override suspend fun fetchTracks(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedTrack>? {
        val market = getMarket()
        Log.d(TAG, "fetchTracks: market=$market, localCount=$localCount, latestExternalId=$latestExternalId")
        val probe = withRetry { spotifyClient.getLikedTracks(limit = 1, market = market) }
        val probeTrackId = probe.items.firstOrNull()?.track?.id
        Log.d(TAG, "fetchTracks probe: total=${probe.total}, firstTrackId=$probeTrackId, firstTrackName=${probe.items.firstOrNull()?.track?.name}")
        if (probe.total == localCount && probeTrackId == latestExternalId) {
            Log.d(TAG, "Tracks unchanged (count=$localCount), skipping full fetch")
            return null
        }
        Log.d(TAG, "fetchTracks: changes detected (remote=${probe.total} vs local=$localCount, latestId match=${probeTrackId == latestExternalId}), doing full fetch")

        val all = mutableListOf<SyncedTrack>()
        var offset = 0
        val total = probe.total
        var skippedNullTracks = 0
        onProgress?.invoke(0, total)

        while (offset < total) {
            val page = withRetry { spotifyClient.getLikedTracks(limit = BATCH_SIZE, offset = offset, market = market) }
            if (page.items.isEmpty()) break
            page.items.forEach { saved ->
                val track = saved.track
                if (track == null) {
                    skippedNullTracks++
                    return@forEach
                }
                val spotifyAddedAt = parseIsoTimestamp(saved.addedAt)
                // Spotify local files have null id — generate a stable synthetic ID
                val isLocalFile = track.id == null
                val trackId = track.id ?: run {
                    val key = "local:${track.name.orEmpty()}:${track.artistName}"
                    key.hashCode().toUInt().toString(16)
                }
                all.add(SyncedTrack(
                    entity = TrackEntity(
                        id = "spotify-$trackId",
                        title = track.name ?: "Unknown",
                        artist = track.artistName,
                        album = track.album?.name,
                        albumId = track.album?.id?.let { "spotify-$it" },
                        duration = track.durationMs,
                        artworkUrl = track.album?.images?.bestImageUrl(),
                        spotifyUri = if (!isLocalFile) "spotify:track:$trackId" else null,
                        spotifyId = track.id,
                        resolver = if (!isLocalFile) "spotify" else "localfiles",
                        sourceType = "synced",
                        addedAt = spotifyAddedAt,
                    ),
                    spotifyId = trackId,
                    addedAt = spotifyAddedAt,
                ))
            }
            offset += BATCH_SIZE
            onProgress?.invoke(all.size, total)
        }

        Log.d(TAG, "fetchTracks complete: fetched=${all.size}/$total, skippedNull=$skippedNullTracks")
        // Final progress with actual count (some tracks are null/unavailable in market)
        onProgress?.invoke(all.size, all.size)
        return all
    }

    override suspend fun fetchAlbums(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedAlbum>? {
        val market = getMarket()
        val probe = withRetry { spotifyClient.getSavedAlbums(limit = 1, market = market) }
        if (probe.total == localCount && probe.items.firstOrNull()?.album?.id == latestExternalId) {
            return null
        }

        val all = mutableListOf<SyncedAlbum>()
        var offset = 0
        val total = probe.total

        while (offset < total) {
            val page = withRetry { spotifyClient.getSavedAlbums(limit = BATCH_SIZE, offset = offset, market = market) }
            if (page.items.isEmpty()) break
            page.items.forEach { saved ->
                val album = saved.album ?: return@forEach
                val albumId = album.id ?: return@forEach
                val spotifyAddedAt = parseIsoTimestamp(saved.addedAt)
                all.add(SyncedAlbum(
                    entity = AlbumEntity(
                        id = "spotify-$albumId",
                        title = album.name ?: "Unknown Album",
                        artist = album.artistName,
                        artworkUrl = album.images.bestImageUrl(),
                        year = album.year,
                        trackCount = album.totalTracks,
                        spotifyId = albumId,
                        addedAt = spotifyAddedAt,
                    ),
                    spotifyId = albumId,
                    addedAt = spotifyAddedAt,
                ))
            }
            offset += BATCH_SIZE
            onProgress?.invoke(all.size, total)
        }

        if (all.size < total) {
            Log.w(TAG, "Album pagination incomplete: fetched ${all.size}/$total albums")
        }

        return all
    }

    override suspend fun fetchArtists(
        localCount: Int,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedArtist>? {
        val all = mutableListOf<SyncedArtist>()
        var after: String? = null
        var total = 0

        do {
            val response = withRetry { spotifyClient.getFollowedArtists(after = after) }
            total = response.artists.total
            response.artists.items.forEach { artist ->
                val artistId = artist.id ?: return@forEach
                all.add(SyncedArtist(
                    entity = ArtistEntity(
                        id = "spotify-$artistId",
                        name = artist.name ?: "Unknown Artist",
                        imageUrl = artist.images.bestImageUrl(),
                        spotifyId = artistId,
                        genres = artist.genres.joinToString(","),
                    ),
                    spotifyId = artistId,
                ))
            }
            after = response.artists.cursors?.after
            onProgress?.invoke(all.size, total)
        } while (after != null)

        if (all.size == localCount) return null
        return all
    }

    override suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedPlaylist> {
        val currentUser = withRetry { spotifyClient.getCurrentUser() }
        val all = mutableListOf<SyncedPlaylist>()
        val seen = mutableSetOf<String>()
        var offset = 0

        val probe = withRetry { spotifyClient.getUserPlaylists(limit = 1) }
        val total = probe.total

        while (offset < total) {
            val page = withRetry { spotifyClient.getUserPlaylists(limit = BATCH_SIZE, offset = offset) }
            if (page.items.isEmpty()) break
            page.items.forEach { playlist ->
                val playlistId = playlist.id ?: return@forEach
                if (seen.add(playlistId)) {
                    all.add(SyncedPlaylist(
                        entity = PlaylistEntity(
                            id = "spotify-$playlistId",
                            name = playlist.name ?: "Untitled Playlist",
                            description = playlist.description,
                            artworkUrl = playlist.images.bestImageUrl(),
                            trackCount = playlist.tracks?.total ?: 0,
                            spotifyId = playlistId,
                            snapshotId = playlist.snapshotId,
                            ownerName = playlist.owner?.displayName,
                        ),
                        spotifyId = playlistId,
                        snapshotId = playlist.snapshotId,
                        trackCount = playlist.tracks?.total ?: 0,
                        isOwned = playlist.owner?.id == currentUser.id,
                    ))
                }
            }
            offset += BATCH_SIZE
            onProgress?.invoke(all.size, total)
        }

        return all
    }

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<PlaylistTrackEntity> {
        val all = mutableListOf<PlaylistTrackEntity>()
        var offset = 0
        val localPlaylistId = "spotify-$externalPlaylistId"

        val market = getMarket()
        val probe = withRetry { spotifyClient.getPlaylistTracks(externalPlaylistId, limit = 1, market = market) }
        val total = probe.total

        while (offset < total) {
            val page = withRetry {
                spotifyClient.getPlaylistTracks(externalPlaylistId, limit = PLAYLIST_TRACK_BATCH_SIZE, offset = offset, market = market)
            }
            if (page.items.isEmpty()) break
            page.items.forEach { item ->
                val track = item.track ?: return@forEach
                all.add(PlaylistTrackEntity(
                    playlistId = localPlaylistId,
                    position = all.size,
                    trackTitle = track.name ?: "Unknown",
                    trackArtist = track.artistName,
                    trackAlbum = track.album?.name,
                    trackDuration = track.durationMs,
                    trackArtworkUrl = track.album?.images?.bestImageUrl(),
                    trackResolver = "spotify",
                    trackSpotifyUri = track.id?.let { "spotify:track:$it" },
                    trackSpotifyId = track.id,
                    addedAt = parseIsoTimestamp(item.addedAt),
                ))
            }
            offset += PLAYLIST_TRACK_BATCH_SIZE
        }

        return all
    }

    override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? {
        return try {
            val playlist = withRetry {
                spotifyClient.getPlaylist(externalPlaylistId, fields = "snapshot_id")
            }
            playlist.snapshotId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get snapshot for $externalPlaylistId", e)
            null
        }
    }

    // ── Write operations ─────────────────────────────────────────

    override suspend fun saveTracks(externalIds: List<String>) {
        externalIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyClient.saveTracks(SpIdsRequest(batch)) }
        }
    }

    override suspend fun removeTracks(externalIds: List<String>) {
        externalIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyClient.removeTracks(SpIdsRequest(batch)) }
        }
    }

    override suspend fun saveAlbums(externalIds: List<String>) {
        externalIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyClient.saveAlbums(SpIdsRequest(batch)) }
        }
    }

    override suspend fun removeAlbums(externalIds: List<String>) {
        externalIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyClient.removeAlbums(SpIdsRequest(batch)) }
        }
    }

    override suspend fun followArtists(externalIds: List<String>) {
        externalIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyClient.followArtists(body = SpIdsRequest(batch)) }
        }
    }

    override suspend fun unfollowArtists(externalIds: List<String>) {
        externalIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyClient.unfollowArtists(body = SpIdsRequest(batch)) }
        }
    }

    override suspend fun createPlaylist(name: String, description: String?): com.parachord.shared.sync.RemoteCreated {
        val user = withRetry { spotifyClient.getCurrentUser() }
        val created = withRetry {
            spotifyClient.createPlaylist(user.id, SpCreatePlaylistRequest(name, description))
        }
        val externalId = created.id
            ?: throw IllegalStateException("Spotify create returned null id")
        return com.parachord.shared.sync.RemoteCreated(
            externalId = externalId,
            snapshotId = created.snapshotId,
        )
    }

    override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? {
        if (externalTrackIds.isEmpty()) {
            withRetry { spotifyClient.replacePlaylistTracks(externalPlaylistId, SpUrisRequest(emptyList())) }
            return null
        }

        val chunks = externalTrackIds.chunked(PLAYLIST_TRACK_BATCH_SIZE)
        var snapshotId: String? = null

        chunks.forEachIndexed { index, chunk ->
            val response = if (index == 0) {
                withRetry { spotifyClient.replacePlaylistTracks(externalPlaylistId, SpUrisRequest(chunk)) }
            } else {
                withRetry { spotifyClient.addPlaylistTracks(externalPlaylistId, SpUrisRequest(chunk)) }
            }
            if (response.status.isSuccess()) {
                snapshotId = response.body<SpSnapshotResponse>().snapshotId
            }
        }

        return snapshotId
    }

    override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {
        withRetry {
            spotifyClient.updatePlaylistDetails(externalPlaylistId, SpUpdatePlaylistRequest(name, description))
        }
    }

    override suspend fun deletePlaylist(externalPlaylistId: String): com.parachord.shared.sync.DeleteResult {
        return try {
            withRetry { spotifyClient.unfollowPlaylist(externalPlaylistId) }
            com.parachord.shared.sync.DeleteResult.Success
        } catch (e: Exception) {
            com.parachord.shared.sync.DeleteResult.Failed(e)
        }
    }
}
