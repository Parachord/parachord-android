package com.parachord.android.sync

import android.util.Log
import com.parachord.shared.db.ParachordDb
import com.parachord.android.data.db.dao.*
import com.parachord.android.data.db.entity.*
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.sync.Mutex

class SyncEngine constructor(
    private val db: ParachordDb,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncSourceDao: SyncSourceDao,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
    private val syncPlaylistSourceDao: SyncPlaylistSourceDao,
    private val settingsStore: SettingsStore,
    /**
     * Multi-provider sync surface (Phase 2). Today only Spotify is
     * registered; Phase 4 will add Apple Music. Method bodies still
     * pull `spotifyProvider` out of the list — the iteration over
     * every enabled provider is Phase 3 work. The `first { it.id == "spotify" }
     * + cast` indirection goes away when those bodies are generalized.
     */
    private val providers: List<com.parachord.shared.sync.SyncProvider>,
) {

    private val spotifyProvider: SpotifySyncProvider
        get() = providers.first { it.id == SpotifySyncProvider.PROVIDER_ID } as SpotifySyncProvider
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

        /**
         * Backfill `sync_playlist_source` from playlist rows whose id prefix
         * identifies a provider pull source. Currently handles the `spotify-`
         * prefix — the convention used by [SpotifySyncProvider.fetchPlaylists]
         * when importing remote playlists into local rows. Idempotent; safe to
         * call on every sync.
         *
         * Mirrors [migrateLinksFromPlaylists] but writes the `syncedFrom`
         * single-source table instead of the per-provider `syncedTo` link map.
         * Where `sync_playlist_link` answers "which remote(s) do we push this
         * local playlist to?", `sync_playlist_source` answers "which remote did
         * this local playlist originally come from?".
         *
         * Defensive: a `spotify-` playlist with a null `spotifyId` column (mid-
         * migration legacy data) is skipped rather than writing a source row
         * with an empty externalId. `local-*` and `hosted-xspf-*` rows are
         * ignored entirely — they may have a `spotifyId` for push, but that's
         * a push target, not a pull source.
         */
        // static for test isolation — avoids constructing SyncEngine with unrelated deps
        suspend fun migrateSourceFromPlaylists(
            db: ParachordDb,
            sourceDao: SyncPlaylistSourceDao,
        ) {
            val providerId = SpotifySyncProvider.PROVIDER_ID
            val now = System.currentTimeMillis()
            var added = 0
            for (playlist in db.playlistQueries.getAll().executeAsList()) {
                if (!playlist.id.startsWith("$providerId-")) continue
                val spotifyId = playlist.spotifyId ?: continue
                val existing = sourceDao.selectForLocal(playlist.id)
                if (existing != null
                    && existing.providerId == providerId
                    && existing.externalId == spotifyId
                    && existing.snapshotId == playlist.snapshotId // both-null snapshotId is a legitimate match (Kotlin == → null-safe equals)
                ) continue
                sourceDao.upsert(
                    localPlaylistId = playlist.id,
                    providerId = providerId,
                    externalId = spotifyId,
                    snapshotId = playlist.snapshotId,
                    ownerId = null, // playlist row has ownerName but no ownerId; source ownerId populated at pull time
                    syncedAt = if (playlist.lastModified > 0) playlist.lastModified else now,
                )
                added++
            }
            if (added > 0) {
                Log.d(TAG, "Backfilled $added playlist source(s) into sync_playlist_source")
            }
        }
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
    ): FullSyncResult {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) {
            return FullSyncResult(success = false, error = "Sync not enabled")
        }

        // Skip-if-held (not wait-if-held): the in-app timer, WorkManager, and
        // manual sync can all fire at once. Running them serially would waste
        // work and — before the three-layer dedup — produce remote duplicates.
        if (!syncMutex.tryLock()) {
            Log.i(TAG, "Sync already in progress, skipping")
            return FullSyncResult(success = false, error = "Sync already in progress")
        }

        return try {
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
        } finally {
            syncMutex.unlock()
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
        remote: List<SyncedTrack>,
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
        run {
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
        remote: List<SyncedAlbum>,
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

        run {
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

        run {
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

    /**
     * Backfill [syncPlaylistLinkDao] from any playlist rows that already have
     * a `spotifyId` set. Idempotent — safe to run on every sync. Ensures the
     * durable link map survives upgrades from pre-link-map installs and stays
     * in sync if an older push path wrote `spotifyId` without writing a link.
     */
    private suspend fun migrateLinksFromPlaylists() {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        var added = 0
        for (playlist in playlistDao.getAllSync()) {
            val spotifyId = playlist.spotifyId ?: continue
            val existing = syncPlaylistLinkDao.selectForLink(playlist.id, providerId)
            if (existing?.externalId == spotifyId) continue
            syncPlaylistLinkDao.upsert(
                localPlaylistId = playlist.id,
                providerId = providerId,
                externalId = spotifyId,
                syncedAt = if (playlist.lastModified > 0) playlist.lastModified
                else System.currentTimeMillis(),
            )
            added++
        }
        if (added > 0) {
            Log.d(TAG, "Backfilled $added playlist link(s) from spotifyId into sync_playlist_link")
        }
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

        // Idempotent startup migration: make sure any playlist that already
        // has a `spotifyId` set has a matching row in `sync_playlist_link`.
        // Cheap (single query + conditional upsert) and guarantees the
        // durable map is populated even if older syncs wrote `spotifyId`
        // without writing a link. Mirrors desktop's
        // migrateSyncLinksFromPlaylists (main.js).
        migrateLinksFromPlaylists()

        // Sibling backfill for the per-playlist pull-source table. Any
        // `spotify-<externalId>` playlist row from a prior import that
        // predates Phase 1's `sync_playlist_source` table gets a
        // `syncedFrom` row so future pull cycles have a stable key beyond
        // the id-prefix convention.
        migrateSourceFromPlaylists(db, syncPlaylistSourceDao)

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
                val isHosted = localPlaylist.sourceUrl != null

                when {
                    // Hosted XSPF: XSPF is canonical. Only push (when the
                    // poller flipped locallyModified); never pull, because the
                    // next poll tick would revert any pulled Spotify state
                    // within 5 minutes anyway. Desktop parity.
                    isHosted && localModified -> {
                        pushPlaylist(localPlaylist)
                        updated++
                    }
                    isHosted -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = System.currentTimeMillis()))
                        unchanged++
                    }
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
                            // Prune the durable link map too so the next sync
                            // doesn't burn a validation round-trip on a
                            // known-dead externalId.
                            syncPlaylistLinkDao.deleteByExternalId(providerId, dupe.spotifyId)
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

        // Push local-only playlists to Spotify.
        // Only push playlists the user explicitly created in the app (id starts
        // with "local-") and hosted XSPF playlists (desktop parity — the XSPF
        // is canonical, Spotify is a passive mirror). Other sources
        // (ListenBrainz weekly, DJ chat, spotify-import, applemusic-import)
        // should not be auto-pushed — they'd create unwanted duplicates and
        // potentially empty-named playlists on Spotify.
        if (settings.pushLocalPlaylists) {
            val allPlaylists = playlistDao.getAllSync()
            val localOnly = allPlaylists.filter {
                it.spotifyId == null &&
                    (it.id.startsWith("local-") || it.sourceUrl != null) &&
                    it.name.isNotBlank()
            }
            // Indexes into the single remotePlaylists fetch — reused by all
            // three dedup layers so we don't hit the Spotify API again.
            val liveRemote = remotePlaylists.filter { it.spotifyId !in deletedSpotifyIds }
            val remoteById = liveRemote.associateBy { it.spotifyId }
            val ownedRemoteByName = liveRemote.filter { it.isOwned }
                .groupBy { it.entity.name.trim().lowercase() }

            for (playlist in localOnly) {
                try {
                    // Phase 3 — Fix 3 + pendingAction skip:
                    // Skip rows the user marked remote-deleted. A `pendingAction`
                    // means the linked remote was deleted on the provider side
                    // and we must NOT silently recreate it; the playlist detail
                    // banner will let the user re-push or unlink. Lift the
                    // link lookup here so the dedup-Layer-1 block below can
                    // reuse it without a second query.
                    val link = syncPlaylistLinkDao.selectForLink(playlist.id, providerId)
                    if (link?.pendingAction != null) {
                        Log.d(TAG, "Skipping push for ${playlist.id} on $providerId — pendingAction=${link.pendingAction}")
                        continue
                    }

                    // Phase 3 — Fix 3: provider-scoped `syncedFrom` guard.
                    // Defensive today (single-provider Spotify push only matches
                    // local-* and hosted-* playlists, which have no syncedFrom
                    // entry); load-bearing the moment Phase 4 iterates Apple
                    // Music too. Without this, a Spotify-imported playlist
                    // would later get pushed back to Spotify by the AM loop's
                    // counterpart and create duplicates. Pattern: only skip
                    // when the CURRENT push target equals the playlist's pull
                    // source — never blanket-skip on `syncedFrom != null`.
                    val pullSource = syncPlaylistSourceDao.selectForLocal(playlist.id)
                    if (pullSource?.providerId == providerId) {
                        Log.d(TAG, "Skipping push for ${playlist.id} on $providerId — its pull source")
                        continue
                    }

                    // Three-layer dedup — mirrors desktop's sync:create-playlist
                    // IPC handler. Each layer yields an `existing` remote we
                    // can link to instead of creating a duplicate. Stop at the
                    // first hit; fall through to create only if all three miss.

                    // Layer 1: durable sync_playlist_link map. Survives any
                    // save path that drops `spotifyId` on the local row.
                    // (Note: `link` was already loaded above for the
                    // pendingAction skip; reuse it here.)
                    var existing: SyncedPlaylist? = null
                    var matchSource = ""
                    if (link != null) {
                        val fromLink = remoteById[link.externalId]
                        if (fromLink != null && fromLink.isOwned) {
                            existing = fromLink
                            matchSource = "id-link"
                        } else {
                            // Remote deleted externally — drop the stale link
                            // and fall through so we don't keep trying to
                            // reuse a dead ID on every sync.
                            Log.d(TAG, "Stored link $providerId:${link.externalId} for ${playlist.id} no longer exists remotely; clearing")
                            syncPlaylistLinkDao.deleteForLink(playlist.id, providerId)
                        }
                    }

                    // Layer 2: playlist.spotifyId (redundant here because the
                    // filter above already excluded non-null spotifyId rows —
                    // kept for structural parity with desktop, in case the
                    // filter changes).
                    if (existing == null && playlist.spotifyId != null) {
                        val fromField = remoteById[playlist.spotifyId]
                        if (fromField != null && fromField.isOwned) {
                            existing = fromField
                            matchSource = "playlist-field"
                        }
                    }

                    // Layer 3: name match (case-insensitive, trimmed, owned).
                    // If several match, the richest wins so the user's most
                    // populated playlist becomes the canonical target.
                    if (existing == null) {
                        val candidates = ownedRemoteByName[playlist.name.trim().lowercase()]
                        val pick = candidates?.maxByOrNull { it.trackCount }
                        if (pick != null) {
                            existing = pick
                            matchSource = if ((candidates.size) > 1)
                                "name-match (${candidates.size} candidates)"
                            else "name-match"
                        }
                    }

                    val spotifyId: String
                    val snapshotId: String?
                    if (existing != null) {
                        Log.d(TAG, "Linked '${playlist.name}' to existing $providerId playlist ${existing.spotifyId} via $matchSource")
                        spotifyId = existing.spotifyId
                        snapshotId = existing.snapshotId
                    } else {
                        val created = spotifyProvider.createPlaylistOnSpotify(
                            playlist.name, playlist.description
                        )
                        spotifyId = created.id ?: continue
                        snapshotId = created.snapshotId
                        Log.d(TAG, "Created $providerId playlist '${playlist.name}' ($spotifyId)")
                    }

                    // Write the durable link IMMEDIATELY — before track push
                    // and before the playlist row update. If either of those
                    // fails, the link still protects the next sync from
                    // creating a fresh duplicate.
                    syncPlaylistLinkDao.upsert(
                        localPlaylistId = playlist.id,
                        providerId = providerId,
                        externalId = spotifyId,
                    )

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
            val localPlaylist = playlistDao.getById(source.itemId)
            // Locally-owned playlists (hosted XSPF — canonical via `sourceUrl`
            // — and user-created `local-*` playlists) must NEVER be deleted by
            // sync cleanup. Spotify is just a downstream mirror for these:
            // a missing remote means "deselected from sync" or "deleted on
            // Spotify externally", neither of which should wipe the user's
            // local row. Skip the entire cleanup so the next push (or next
            // hosted-XSPF poll) can recover the remote if needed.
            if (localPlaylist?.sourceUrl != null ||
                localPlaylist?.id?.startsWith("local-") == true
            ) {
                return@forEach
            }
            syncSourceDao.deleteByKey(source.itemId, "playlist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "playlist")
            if (remaining.isEmpty()) {
                localPlaylist?.let { playlistDao.delete(it) }
                playlistTrackDao.deleteByPlaylistId(source.itemId)
            }
            removed++
        }

        // Phase 3 — Fix 4 (multi-provider mirror propagation):
        // Clear `locallyModified` for any playlist whose relevant mirrors
        // are all caught up. `relevantMirrors` excludes the source
        // provider — the push loop never targets it (Fix 3 guard) so its
        // syncedAt would never advance and would strand the flag forever.
        // Today only Spotify is enabled, but the loop is shaped for Phase
        // 4 to add Apple Music with a one-line set change.
        clearLocallyModifiedFlags(setOf(SpotifySyncProvider.PROVIDER_ID))

        return TypeSyncResult(added = added, removed = removed, updated = updated, unchanged = unchanged)
    }

    /**
     * Sweep `locallyModified` playlists and clear the flag iff every
     * relevant push mirror's `syncedAt` has advanced past the row's
     * `lastModified`. Relevant mirrors are the enabled providers that
     * have a `sync_playlist_link` row for this playlist, EXCLUDING the
     * pull source (since the push loop's Fix 3 guard never pushes back
     * to source). When `relevantMirrors` is empty (e.g. source-only
     * playlists), the flag clears immediately.
     *
     * Mirrors desktop's post-push clear logic (app.js L5916+).
     */
    private suspend fun clearLocallyModifiedFlags(enabledProviders: Set<String>) {
        val candidates = playlistDao.getLocallyModified()
        for (playlist in candidates) {
            val sourceProvider = syncPlaylistSourceDao.selectForLocal(playlist.id)?.providerId
            val mirrors = syncPlaylistLinkDao.selectForLocal(playlist.id)
            val relevantMirrors = mirrors.filter { link ->
                link.providerId in enabledProviders && link.providerId != sourceProvider
            }
            if (relevantMirrors.isEmpty()) {
                playlistDao.clearLocallyModified(playlist.id)
                continue
            }
            val allCaught = relevantMirrors.all { it.syncedAt >= playlist.lastModified }
            if (allCaught) playlistDao.clearLocallyModified(playlist.id)
        }
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
        remote: SyncedPlaylist,
    ) {
        // Fetch tracks first, then update playlist metadata with actual count
        // (use update, not insert/REPLACE, because REPLACE does DELETE+INSERT
        // which CASCADE-deletes playlist_tracks)
        val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
        playlistTrackDao.deleteByPlaylistId(localPlaylist.id)
        playlistTrackDao.insertAll(tracks)

        val now = System.currentTimeMillis()
        // Fix 1 (multi-provider mirror propagation): a pull replaces the
        // local tracks with the remote's. If this playlist also has push-
        // mirror entries on OTHER providers, those copies are now stale.
        // Flag locallyModified so the next push loop catches them up.
        // Without this, an Android-edit → Spotify → desktop pull stops
        // at the desktop and never reaches Apple Music.
        val hasOtherMirrors = syncPlaylistLinkDao.hasOtherMirrors(
            localPlaylist.id, SpotifySyncProvider.PROVIDER_ID,
        )
        playlistDao.update(localPlaylist.copy(
            name = remote.entity.name,
            description = remote.entity.description,
            artworkUrl = remote.entity.artworkUrl,
            trackCount = tracks.size,
            snapshotId = remote.snapshotId,
            updatedAt = now,
            lastModified = now,
            locallyModified = hasOtherMirrors,
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
                spotifyProvider.removeTracks(listOf(spotifySource.externalId!!))
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
                spotifyProvider.removeAlbums(listOf(spotifySource.externalId!!))
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
                spotifyProvider.unfollowArtists(listOf(spotifySource.externalId!!))
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
        syncPlaylistLinkDao.deleteForProvider(providerId)
        settingsStore.clearSyncSettings()
    }
}
