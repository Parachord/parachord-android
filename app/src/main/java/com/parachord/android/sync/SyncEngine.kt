package com.parachord.android.sync

import android.util.Log
import androidx.room.withTransaction
import com.parachord.android.data.db.ParachordDatabase
import com.parachord.android.data.db.dao.*
import com.parachord.android.data.db.entity.*
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncEngine @Inject constructor(
    private val db: ParachordDatabase,
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
        /**
         * Bump this to force a full re-fetch on next sync (bypasses quick-check).
         * The current version is stored in DataStore; when it's less than this,
         * localCount is passed as 0 to force a full diff.
         */
        private const val SYNC_DATA_VERSION = 4
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

        // One-time fixup (v2→v3): wipe synced tracks and re-fetch cleanly to fix
        // duplicates + correct addedAt timestamps from Spotify
        if (settingsStore.getSyncDataVersion() < 3) {
            Log.d(TAG, "v3 migration: clearing synced tracks for clean re-sync")
            syncSourceDao.deleteByProviderAndType(providerId, "track")
            trackDao.deleteSyncedTracks()
            val result = syncTracksClean(onProgress)
            // Set to 3, not SYNC_DATA_VERSION — playlist dedup (v4) runs separately
            settingsStore.setSyncDataVersion(3)
            return result
        }

        val remote = spotifyProvider.fetchTracks(
            localCount = localCount,
            latestExternalId = latest?.externalId,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing liked songs..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        return applyTrackDiff(remote, localSources, providerId)
    }

    /** Clean sync after wiping synced tracks — no local sources to diff against. */
    private suspend fun syncTracksClean(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val remote = spotifyProvider.fetchTracks(
            localCount = 0,
            latestExternalId = null,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing liked songs..."))
            },
        ) ?: return TypeSyncResult()

        return applyTrackDiff(remote, emptyList(), providerId)
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
        Log.d(TAG, "applyTrackDiff: remote=${remote.size}, localSources=${localSources.size}, toAdd=${toAdd.size}, toRemove=${toRemove.size}, toUpdate=${toUpdate.size}")

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: would remove ${toRemove.size}/${localSources.size} tracks, skipping removals")
            toRemove = emptyList()
        }

        val now = System.currentTimeMillis()

        // Batch all writes in a single transaction so Room emits only one Flow update
        db.withTransaction {
            if (toAdd.isNotEmpty()) {
                trackDao.insertAll(toAdd.map { it.entity })
                syncSourceDao.insertAll(toAdd.map { synced ->
                    SyncSourceEntity(
                        itemId = synced.entity.id,
                        itemType = "track",
                        providerId = providerId,
                        externalId = synced.spotifyId,
                        addedAt = synced.addedAt,
                        syncedAt = now,
                    )
                })
            }

            toRemove.forEach { source ->
                syncSourceDao.deleteByKey(source.itemId, "track", providerId)
                val remaining = syncSourceDao.getByItem(source.itemId, "track")
                if (remaining.isEmpty()) {
                    trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
                }
            }

            if (toUpdate.isNotEmpty()) {
                trackDao.insertAll(toUpdate.map { it.entity })
                syncSourceDao.insertAll(toUpdate.map { synced ->
                    val existing = localByExternalId[synced.spotifyId]!!
                    existing.copy(syncedAt = now)
                })
            }
        }

        return TypeSyncResult(
            added = toAdd.size,
            removed = toRemove.size,
            updated = toUpdate.size,
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

        val now = System.currentTimeMillis()

        db.withTransaction {
            if (toAdd.isNotEmpty()) {
                albumDao.insertAll(toAdd.map { it.entity })
                syncSourceDao.insertAll(toAdd.map { synced ->
                    SyncSourceEntity(
                        itemId = synced.entity.id,
                        itemType = "album",
                        providerId = providerId,
                        externalId = synced.spotifyId,
                        addedAt = synced.addedAt,
                        syncedAt = now,
                    )
                })
            }

            toRemove.forEach { source ->
                syncSourceDao.deleteByKey(source.itemId, "album", providerId)
                val remaining = syncSourceDao.getByItem(source.itemId, "album")
                if (remaining.isEmpty()) {
                    albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
                }
            }

            if (unchanged.isNotEmpty()) {
                albumDao.insertAll(unchanged.map { it.entity })
                syncSourceDao.insertAll(unchanged.mapNotNull { synced ->
                    localByExternalId[synced.spotifyId]?.copy(syncedAt = now)
                })
            }
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, updated = unchanged.size)
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

        val now = System.currentTimeMillis()

        db.withTransaction {
            if (toAdd.isNotEmpty()) {
                artistDao.insertAll(toAdd.map { it.entity })
                syncSourceDao.insertAll(toAdd.map { synced ->
                    SyncSourceEntity(
                        itemId = synced.entity.id,
                        itemType = "artist",
                        providerId = providerId,
                        externalId = synced.spotifyId,
                        syncedAt = now,
                    )
                })
            }

            toRemove.forEach { source ->
                syncSourceDao.deleteByKey(source.itemId, "artist", providerId)
                val remaining = syncSourceDao.getByItem(source.itemId, "artist")
                if (remaining.isEmpty()) {
                    artistDao.deleteById(source.itemId)
                }
            }

            if (unchanged.isNotEmpty()) {
                artistDao.insertAll(unchanged.map { it.entity })
                syncSourceDao.insertAll(unchanged.mapNotNull { synced ->
                    localByExternalId[synced.spotifyId]?.copy(syncedAt = now)
                })
            }
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, updated = unchanged.size)
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

        for ((index, remote) in selectedRemote.withIndex()) {
            onProgress(SyncProgress(
                SyncPhase.PLAYLISTS,
                index + 1,
                selectedRemote.size,
                "Syncing playlist: ${remote.entity.name}",
            ))
            val existingSource = localByExternalId[remote.spotifyId]

            if (existingSource == null) {
                val now = System.currentTimeMillis()
                // Check if a playlist with this spotifyId already exists under a
                // different primary key (e.g. pushed local playlist or import).
                // If so, merge into the existing entry to avoid duplicates.
                val existingBySpotifyId = playlistDao.getBySpotifyId(remote.spotifyId)
                val targetId = existingBySpotifyId?.id ?: remote.entity.id

                if (existingBySpotifyId != null && existingBySpotifyId.id != remote.entity.id) {
                    // Remove the old entry's tracks — they'll be replaced below
                    playlistTrackDao.deleteByPlaylistId(existingBySpotifyId.id)
                    playlistDao.delete(existingBySpotifyId)
                    // Clean up any orphaned sync source for the old ID
                    syncSourceDao.deleteAllForItem(existingBySpotifyId.id, "playlist")
                }

                playlistDao.insert(remote.entity.copy(
                    id = targetId,
                    createdAt = existingBySpotifyId?.createdAt ?: now,
                    updatedAt = now,
                    lastModified = now,
                ))
                val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
                playlistTrackDao.deleteByPlaylistId(targetId)
                val remappedTracks = if (targetId != remote.entity.id) {
                    tracks.map { it.copy(playlistId = targetId) }
                } else {
                    tracks
                }
                playlistTrackDao.insertAll(remappedTracks)
                syncSourceDao.insert(SyncSourceEntity(
                    itemId = targetId,
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

        // One-time cleanup of duplicate playlists on Spotify created by earlier
        // sync bugs. Only runs during the v3→v4 migration, not on every sync.
        val deletedSpotifyIds = mutableSetOf<String>()
        if (settingsStore.getSyncDataVersion() < SYNC_DATA_VERSION) {
            val currentLocalSources = syncSourceDao.getByProvider(providerId, "playlist")
            val ownedByName = remotePlaylists.filter { it.isOwned }
                .groupBy { it.entity.name.lowercase() }
            val dupeGroups = ownedByName.values.filter { it.size > 1 }
            if (dupeGroups.isNotEmpty()) {
                val totalDupes = dupeGroups.sumOf { it.size - 1 }
                var dedupProgress = 0
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, 0, totalDupes, "Cleaning up duplicate playlists..."))

                for (dupes in dupeGroups) {
                    val trackedIds = currentLocalSources.mapNotNull { it.externalId }.toSet()
                    val tracked = dupes.filter { it.spotifyId in trackedIds }
                    val keep = tracked.firstOrNull() ?: dupes.first()
                    for (dupe in dupes) {
                        if (dupe.spotifyId == keep.spotifyId) continue
                        try {
                            Log.d(TAG, "Removing duplicate Spotify playlist: ${dupe.entity.name} (${dupe.spotifyId})")
                            spotifyProvider.deletePlaylist(dupe.spotifyId)
                            deletedSpotifyIds.add(dupe.spotifyId)
                            val orphanSource = currentLocalSources.find { it.externalId == dupe.spotifyId }
                            if (orphanSource != null) {
                                syncSourceDao.deleteByKey(orphanSource.itemId, "playlist", providerId)
                            }
                            removed++
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to remove duplicate playlist ${dupe.spotifyId}", e)
                        }
                        dedupProgress++
                        onProgress(SyncProgress(SyncPhase.PLAYLISTS, dedupProgress, totalDupes, "Cleaning up duplicate playlists..."))
                    }
                }
            }
            settingsStore.setSyncDataVersion(SYNC_DATA_VERSION)
        }

        // Push local-only playlists to Spotify
        if (settings.pushLocalPlaylists) {
            val allPlaylists = playlistDao.getAllSync()
            val localOnly = allPlaylists.filter { it.spotifyId == null }
            // Build a lookup of owned remote playlists by name for reuse,
            // excluding playlists we just deleted during dedup above.
            val ownedRemoteByName = remotePlaylists.filter { it.isOwned && it.spotifyId !in deletedSpotifyIds }
                .associateBy { it.entity.name.lowercase() }

            for (playlist in localOnly) {
                try {
                    // Check if an owned playlist with this name already exists
                    // on Spotify (e.g. from a previous push before sync_sources
                    // were cleared). Reuse it instead of creating a duplicate.
                    val existing = ownedRemoteByName[playlist.name.lowercase()]
                    val spotifyId: String
                    val snapshotId: String?
                    if (existing != null) {
                        Log.d(TAG, "Reusing existing Spotify playlist '${playlist.name}' (${existing.spotifyId})")
                        spotifyId = existing.spotifyId
                        snapshotId = existing.snapshotId
                    } else {
                        val created = spotifyProvider.createPlaylistOnSpotify(
                            playlist.name, playlist.description
                        )
                        spotifyId = created.id ?: continue
                        snapshotId = created.snapshotId
                    }
                    val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
                    val uris = tracks.mapNotNull { it.trackSpotifyUri }
                    if (uris.isNotEmpty()) {
                        spotifyProvider.replacePlaylistTracks(spotifyId, uris)
                    }
                    playlistDao.update(playlist.copy(
                        spotifyId = spotifyId,
                        snapshotId = snapshotId,
                        locallyModified = false,
                    ))
                    syncSourceDao.insert(SyncSourceEntity(
                        itemId = playlist.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = spotifyId,
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

        playlistDao.update(playlist.copy(
            snapshotId = snapshotId ?: playlist.snapshotId,
            trackCount = tracks.size,
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
        // Fetch tracks first, then update playlist metadata with actual count
        // (use update, not insert/REPLACE, because REPLACE does DELETE+INSERT
        // which CASCADE-deletes playlist_tracks)
        val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
        playlistTrackDao.deleteByPlaylistId(localPlaylist.id)
        playlistTrackDao.insertAll(tracks)

        val now = System.currentTimeMillis()
        playlistDao.update(localPlaylist.copy(
            name = remote.entity.name,
            description = remote.entity.description,
            artworkUrl = remote.entity.artworkUrl,
            trackCount = tracks.size,
            snapshotId = remote.snapshotId,
            updatedAt = now,
            lastModified = now,
            locallyModified = false,
            ownerName = remote.entity.ownerName,
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
