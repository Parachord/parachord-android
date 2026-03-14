package com.parachord.android.sync

import android.util.Log
import com.parachord.android.data.db.dao.*
import com.parachord.android.data.db.entity.*
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncEngine @Inject constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncSourceDao: SyncSourceDao,
    private val settingsStore: SettingsStore,
    private val spotifyProvider: SpotifySyncProvider,
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val MASS_REMOVAL_THRESHOLD_PERCENT = 0.25
        private const val MASS_REMOVAL_THRESHOLD_COUNT = 50
    }

    private val syncMutex = Mutex()

    data class TypeSyncResult(
        val added: Int = 0,
        val removed: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
    )

    data class FullSyncResult(
        val tracks: TypeSyncResult = TypeSyncResult(),
        val albums: TypeSyncResult = TypeSyncResult(),
        val artists: TypeSyncResult = TypeSyncResult(),
        val playlists: TypeSyncResult = TypeSyncResult(),
        val success: Boolean = true,
        val error: String? = null,
    )

    enum class SyncPhase {
        TRACKS, ALBUMS, ARTISTS, PLAYLISTS, COMPLETE
    }

    data class SyncProgress(
        val phase: SyncPhase,
        val current: Int = 0,
        val total: Int = 0,
        val message: String = "",
    )

    suspend fun syncAll(
        onProgress: (SyncProgress) -> Unit = {},
    ): FullSyncResult = syncMutex.withLock {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) {
            return FullSyncResult(success = false, error = "Sync not enabled")
        }

        try {
            var trackResult = TypeSyncResult()
            var albumResult = TypeSyncResult()
            var artistResult = TypeSyncResult()
            var playlistResult = TypeSyncResult()

            if (settings.syncTracks) {
                onProgress(SyncProgress(SyncPhase.TRACKS, message = "Syncing liked songs..."))
                trackResult = syncTracks(onProgress)
            }

            if (settings.syncAlbums) {
                onProgress(SyncProgress(SyncPhase.ALBUMS, message = "Syncing saved albums..."))
                albumResult = syncAlbums(onProgress)
            }

            if (settings.syncArtists) {
                onProgress(SyncProgress(SyncPhase.ARTISTS, message = "Syncing followed artists..."))
                artistResult = syncArtists(onProgress)
            }

            if (settings.syncPlaylists) {
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, message = "Syncing playlists..."))
                playlistResult = syncPlaylists(settings, onProgress)
            }

            settingsStore.setLastSyncAt(System.currentTimeMillis())

            val result = FullSyncResult(
                tracks = trackResult,
                albums = albumResult,
                artists = artistResult,
                playlists = playlistResult,
            )
            onProgress(SyncProgress(SyncPhase.COMPLETE, message = "Sync complete"))
            Log.d(TAG, "Sync complete: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            FullSyncResult(success = false, error = e.message)
        }
    }

    // ── Track sync ───────────────────────────────────────────────

    private suspend fun syncTracks(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "track")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "track")

        val remote = spotifyProvider.fetchTracks(
            localCount = localCount,
            latestExternalId = latest?.externalId,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing liked songs..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        return applyTrackDiff(remote, localSources, providerId)
    }

    private suspend fun applyTrackDiff(
        remote: List<SpotifySyncProvider.SyncedTrack>,
        localSources: List<SyncSourceEntity>,
        providerId: String,
    ): TypeSyncResult {
        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val toUpdate = remote.filter { synced ->
            synced.spotifyId in localByExternalId
        }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: would remove ${toRemove.size}/${localSources.size} tracks, skipping removals")
            toRemove = emptyList()
        }

        toAdd.forEach { synced ->
            trackDao.insert(synced.entity)
            syncSourceDao.insert(SyncSourceEntity(
                itemId = synced.entity.id,
                itemType = "track",
                providerId = providerId,
                externalId = synced.spotifyId,
                addedAt = synced.addedAt,
                syncedAt = System.currentTimeMillis(),
            ))
        }

        toRemove.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "track", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "track")
            if (remaining.isEmpty()) {
                trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
            }
        }

        toUpdate.forEach { synced ->
            val existing = localByExternalId[synced.spotifyId] ?: return@forEach
            syncSourceDao.insert(existing.copy(syncedAt = System.currentTimeMillis()))
        }

        return TypeSyncResult(
            added = toAdd.size,
            removed = toRemove.size,
            updated = 0,
            unchanged = toUpdate.size,
        )
    }

    // ── Album sync ───────────────────────────────────────────────

    private suspend fun syncAlbums(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "album")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "album")

        val remote = spotifyProvider.fetchAlbums(
            localCount = localCount,
            latestExternalId = latest?.externalId,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.ALBUMS, current, total, "Syncing saved albums..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        return applyAlbumDiff(remote, localSources, providerId)
    }

    private suspend fun applyAlbumDiff(
        remote: List<SpotifySyncProvider.SyncedAlbum>,
        localSources: List<SyncSourceEntity>,
        providerId: String,
    ): TypeSyncResult {
        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val unchanged = remote.filter { it.spotifyId in localByExternalId }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: albums, skipping removals")
            toRemove = emptyList()
        }

        toAdd.forEach { synced ->
            albumDao.insert(synced.entity)
            syncSourceDao.insert(SyncSourceEntity(
                itemId = synced.entity.id,
                itemType = "album",
                providerId = providerId,
                externalId = synced.spotifyId,
                addedAt = synced.addedAt,
                syncedAt = System.currentTimeMillis(),
            ))
        }

        toRemove.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "album", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "album")
            if (remaining.isEmpty()) {
                albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
            }
        }

        unchanged.forEach { synced ->
            val existing = localByExternalId[synced.spotifyId] ?: return@forEach
            syncSourceDao.insert(existing.copy(syncedAt = System.currentTimeMillis()))
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, unchanged = unchanged.size)
    }

    // ── Artist sync ──────────────────────────────────────────────

    private suspend fun syncArtists(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "artist")
        val localCount = localSources.size

        val remote = spotifyProvider.fetchArtists(
            localCount = localCount,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.ARTISTS, current, total, "Syncing followed artists..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val unchanged = remote.filter { it.spotifyId in localByExternalId }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: artists, skipping removals")
            toRemove = emptyList()
        }

        toAdd.forEach { synced ->
            artistDao.insert(synced.entity)
            syncSourceDao.insert(SyncSourceEntity(
                itemId = synced.entity.id,
                itemType = "artist",
                providerId = providerId,
                externalId = synced.spotifyId,
                syncedAt = System.currentTimeMillis(),
            ))
        }

        toRemove.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "artist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "artist")
            if (remaining.isEmpty()) {
                artistDao.deleteById(source.itemId)
            }
        }

        unchanged.forEach { synced ->
            val existing = localByExternalId[synced.spotifyId] ?: return@forEach
            syncSourceDao.insert(existing.copy(syncedAt = System.currentTimeMillis()))
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, unchanged = unchanged.size)
    }

    // ── Playlist sync ────────────────────────────────────────────

    private suspend fun syncPlaylists(
        settings: SettingsStore.SyncSettings,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0

        val remotePlaylists = spotifyProvider.fetchPlaylists { current, total ->
            onProgress(SyncProgress(SyncPhase.PLAYLISTS, current, total, "Fetching playlists..."))
        }

        val selectedRemote = if (settings.selectedPlaylistIds.isEmpty()) {
            remotePlaylists
        } else {
            remotePlaylists.filter { it.spotifyId in settings.selectedPlaylistIds }
        }

        val localSources = syncSourceDao.getByProvider(providerId, "playlist")
        val localByExternalId = localSources.associateBy { it.externalId }

        for (remote in selectedRemote) {
            val existingSource = localByExternalId[remote.spotifyId]

            if (existingSource == null) {
                playlistDao.insert(remote.entity)
                val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
                playlistTrackDao.deleteByPlaylistId(remote.entity.id)
                playlistTrackDao.insertAll(tracks)
                syncSourceDao.insert(SyncSourceEntity(
                    itemId = remote.entity.id,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    syncedAt = System.currentTimeMillis(),
                ))
                added++
            } else {
                val localPlaylist = playlistDao.getById(existingSource.itemId)
                if (localPlaylist == null) {
                    unchanged++
                    continue
                }

                val remoteSnapshotId = remote.snapshotId
                val localSnapshotId = localPlaylist.snapshotId
                val localModified = localPlaylist.locallyModified

                when {
                    localModified && remoteSnapshotId != localSnapshotId -> {
                        val localIsNewer = localPlaylist.lastModified > existingSource.syncedAt
                        if (localIsNewer) {
                            pushPlaylist(localPlaylist)
                        } else {
                            pullPlaylist(localPlaylist, remote)
                        }
                        updated++
                    }
                    localModified -> {
                        pushPlaylist(localPlaylist)
                        updated++
                    }
                    remoteSnapshotId != localSnapshotId -> {
                        pullPlaylist(localPlaylist, remote)
                        updated++
                    }
                    else -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = System.currentTimeMillis()))
                        unchanged++
                    }
                }
            }
        }

        // Push local-only playlists to Spotify
        if (settings.pushLocalPlaylists) {
            val allPlaylists = playlistDao.getAllSync()
            val localOnly = allPlaylists.filter { it.spotifyId == null }

            for (playlist in localOnly) {
                try {
                    val created = spotifyProvider.createPlaylistOnSpotify(
                        playlist.name, playlist.description
                    )
                    val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
                    val uris = tracks.mapNotNull { it.trackSpotifyUri }
                    if (uris.isNotEmpty()) {
                        spotifyProvider.replacePlaylistTracks(created.id, uris)
                    }
                    playlistDao.insert(playlist.copy(
                        spotifyId = created.id,
                        snapshotId = created.snapshotId,
                        locallyModified = false,
                    ))
                    syncSourceDao.insert(SyncSourceEntity(
                        itemId = playlist.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = created.id,
                        syncedAt = System.currentTimeMillis(),
                    ))
                    added++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push playlist ${playlist.name} to Spotify", e)
                }
            }
        }

        // Handle remote removals
        val remoteIds = selectedRemote.map { it.spotifyId }.toSet()
        val removedSources = localSources.filter { it.externalId != null && it.externalId !in remoteIds }
        removedSources.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "playlist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "playlist")
            if (remaining.isEmpty()) {
                playlistDao.getById(source.itemId)?.let { playlistDao.delete(it) }
                playlistTrackDao.deleteByPlaylistId(source.itemId)
            }
            removed++
        }

        return TypeSyncResult(added = added, removed = removed, updated = updated, unchanged = unchanged)
    }

    private suspend fun pushPlaylist(playlist: PlaylistEntity) {
        val spotifyId = playlist.spotifyId ?: return
        val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
        val uris = tracks.mapNotNull { it.trackSpotifyUri }
        val snapshotId = spotifyProvider.replacePlaylistTracks(spotifyId, uris)

        playlistDao.insert(playlist.copy(
            snapshotId = snapshotId ?: playlist.snapshotId,
            locallyModified = false,
        ))

        val source = syncSourceDao.get(playlist.id, "playlist", SpotifySyncProvider.PROVIDER_ID)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun pullPlaylist(
        localPlaylist: PlaylistEntity,
        remote: SpotifySyncProvider.SyncedPlaylist,
    ) {
        val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
        playlistTrackDao.deleteByPlaylistId(localPlaylist.id)
        playlistTrackDao.insertAll(tracks)

        playlistDao.insert(localPlaylist.copy(
            name = remote.entity.name,
            description = remote.entity.description,
            artworkUrl = remote.entity.artworkUrl,
            trackCount = tracks.size,
            snapshotId = remote.snapshotId,
            lastModified = System.currentTimeMillis(),
            locallyModified = false,
        ))

        val source = syncSourceDao.get(localPlaylist.id, "playlist", SpotifySyncProvider.PROVIDER_ID)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = System.currentTimeMillis()))
        }
    }

    // ── Bidirectional removal ────────────────────────────────────

    suspend fun onTrackRemoved(track: TrackEntity) {
        val sources = syncSourceDao.getByItem(track.id, "track")
        val spotifySource = sources.find { it.providerId == SpotifySyncProvider.PROVIDER_ID }
        if (spotifySource?.externalId != null) {
            try {
                spotifyProvider.removeTracks(listOf(spotifySource.externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove track from Spotify", e)
            }
        }
        syncSourceDao.deleteAllForItem(track.id, "track")
    }

    suspend fun onAlbumRemoved(album: AlbumEntity) {
        val sources = syncSourceDao.getByItem(album.id, "album")
        val spotifySource = sources.find { it.providerId == SpotifySyncProvider.PROVIDER_ID }
        if (spotifySource?.externalId != null) {
            try {
                spotifyProvider.removeAlbums(listOf(spotifySource.externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove album from Spotify", e)
            }
        }
        syncSourceDao.deleteAllForItem(album.id, "album")
    }

    suspend fun onArtistRemoved(artist: ArtistEntity) {
        val sources = syncSourceDao.getByItem(artist.id, "artist")
        val spotifySource = sources.find { it.providerId == SpotifySyncProvider.PROVIDER_ID }
        if (spotifySource?.externalId != null) {
            try {
                spotifyProvider.unfollowArtists(listOf(spotifySource.externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unfollow artist on Spotify", e)
            }
        }
        syncSourceDao.deleteAllForItem(artist.id, "artist")
    }

    // ── Stop syncing ─────────────────────────────────────────────

    suspend fun stopSyncing(removeItems: Boolean) {
        val providerId = SpotifySyncProvider.PROVIDER_ID

        if (removeItems) {
            val allSources = syncSourceDao.getAllByProvider(providerId)
            for (source in allSources) {
                val otherSources = syncSourceDao.getByItem(source.itemId, source.itemType)
                    .filter { it.providerId != providerId }
                if (otherSources.isEmpty()) {
                    when (source.itemType) {
                        "track" -> trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
                        "album" -> albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
                        "artist" -> artistDao.deleteById(source.itemId)
                        "playlist" -> {
                            playlistDao.getById(source.itemId)?.let { playlistDao.delete(it) }
                            playlistTrackDao.deleteByPlaylistId(source.itemId)
                        }
                    }
                }
            }
        }

        syncSourceDao.deleteAllForProvider(providerId)
        settingsStore.clearSyncSettings()
    }
}
