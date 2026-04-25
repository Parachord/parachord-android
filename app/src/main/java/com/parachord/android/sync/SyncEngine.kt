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
        // Always run the legacy Spotify-only path first (preserves
        // the v2→v3 migration that wipes + refetches Spotify-tagged
        // tracks; non-Spotify providers don't have that history). The
        // per-provider axis opt-in still applies — Spotify can opt out
        // of tracks via the wizard and this path skips itself.
        var aggregate = if (providerHasAxis(SpotifySyncProvider.PROVIDER_ID, "tracks")) {
            syncTracksForSpotify(onProgress)
        } else TypeSyncResult()

        // Then iterate non-Spotify enabled providers via the generic
        // per-provider helper. Each gates on its own axis opt-in.
        val enabled = settingsStore.getEnabledSyncProviders()
        val others = providers.filter {
            it.id in enabled && it.id != SpotifySyncProvider.PROVIDER_ID
        }
        for (provider in others) {
            if (!providerHasAxis(provider.id, "tracks")) continue
            aggregate += syncTracksForProvider(provider, onProgress)
        }
        return aggregate
    }

    /** Per-provider axis opt-in lookup. Defaults to all axes for back-compat. */
    private suspend fun providerHasAxis(providerId: String, axis: String): Boolean =
        axis in settingsStore.getSyncCollectionsForProvider(providerId)

    private suspend fun syncTracksForSpotify(
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

    /**
     * Generic per-provider track sync. Used for non-Spotify providers
     * (Apple Music; future providers). Spotify keeps its dedicated
     * path because of the v2→v3 migration that's specific to legacy
     * Spotify imports.
     */
    private suspend fun syncTracksForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        val localSources = syncSourceDao.getByProvider(providerId, "track")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "track")
        val remote = try {
            provider.fetchTracks(
                localCount = localCount,
                latestExternalId = latest?.externalId,
                onProgress = { current, total ->
                    onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing ${provider.displayName} library..."))
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch tracks from ${provider.displayName}", e)
            return TypeSyncResult()
        } ?: return TypeSyncResult(unchanged = localCount)
        return applyTrackDiff(remote, localSources, providerId)
    }

    private operator fun TypeSyncResult.plus(other: TypeSyncResult): TypeSyncResult =
        TypeSyncResult(
            added = added + other.added,
            removed = removed + other.removed,
            updated = updated + other.updated,
            unchanged = unchanged + other.unchanged,
        )

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
        val enabled = settingsStore.getEnabledSyncProviders()
        var aggregate = TypeSyncResult()
        for (provider in providers.filter { it.id in enabled }) {
            if (!providerHasAxis(provider.id, "albums")) continue
            aggregate += syncAlbumsForProvider(provider, onProgress)
        }
        return aggregate
    }

    private suspend fun syncAlbumsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        val localSources = syncSourceDao.getByProvider(providerId, "album")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "album")

        // Detect entity-table wipes: if `sync_sources` says we have N
        // albums for this provider but the `albums` table has fewer
        // rows with the matching id-prefix, lie to the provider about
        // localCount so its unchanged-shortcut mismatches and forces a
        // full refetch. Without this, a corrupted entity table never
        // self-heals — the provider keeps short-circuiting on probe
        // matches forever. See "Why did synced Albums/Artists
        // disappear?" debugging session 2026-04-25.
        val entityCount = albumDao.countByIdPrefix("$providerId-")
        val effectiveLocalCount = if (entityCount < localCount) {
            Log.w(TAG, "albums entity-table out of sync for $providerId: " +
                "$entityCount entities vs $localCount sources — forcing refetch")
            0
        } else localCount

        val remote = try {
            provider.fetchAlbums(
                localCount = effectiveLocalCount,
                latestExternalId = if (effectiveLocalCount == 0) null else latest?.externalId,
                onProgress = { current, total ->
                    onProgress(SyncProgress(SyncPhase.ALBUMS, current, total, "Syncing ${provider.displayName} albums..."))
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch albums from ${provider.displayName}", e)
            return TypeSyncResult()
        } ?: return TypeSyncResult(unchanged = localCount)

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
        val enabled = settingsStore.getEnabledSyncProviders()
        var aggregate = TypeSyncResult()
        for (provider in providers.filter { it.id in enabled }) {
            if (!providerHasAxis(provider.id, "artists")) continue
            aggregate += syncArtistsForProvider(provider, onProgress)
        }
        return aggregate
    }

    private suspend fun syncArtistsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        val localSources = syncSourceDao.getByProvider(providerId, "artist")
        val localCount = localSources.size

        // Same entity-table-wipe guard as syncAlbumsForProvider — see
        // its KDoc for context. Force refetch when artist entity rows
        // for this provider's id-prefix fall below the source count.
        val entityCount = artistDao.countByIdPrefix("$providerId-")
        val effectiveLocalCount = if (entityCount < localCount) {
            Log.w(TAG, "artists entity-table out of sync for $providerId: " +
                "$entityCount entities vs $localCount sources — forcing refetch")
            0
        } else localCount

        val remote = try {
            provider.fetchArtists(
                localCount = effectiveLocalCount,
                onProgress = { current, total ->
                    onProgress(SyncProgress(SyncPhase.ARTISTS, current, total, "Syncing ${provider.displayName} artists..."))
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artists from ${provider.displayName}", e)
            return TypeSyncResult()
        } ?: return TypeSyncResult(unchanged = localCount)

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

        // ── Spotify pull (existing block; preserves dedup-cleanup migration) ──
        // The dedup-cleanup migration block below is Spotify-only by design
        // (one-time fix for playlists created by old Spotify sync bugs); it
        // can't be generalized so we keep the Spotify pull inline rather
        // than duplicating the whole helper. When Spotify has opted out
        // of the playlists axis (per-provider opt-in via the wizard),
        // skip the fetch entirely — the push and non-Spotify-pull loops
        // below have their own per-axis gates.
        val spotifyPlaylistsEnabled = providerHasAxis(SpotifySyncProvider.PROVIDER_ID, "playlists")
        val remotePlaylists = if (spotifyPlaylistsEnabled) {
            spotifyProvider.fetchPlaylists { current, total ->
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, current, total, "Fetching playlists..."))
            }
        } else emptyList()

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

                // Phase 3 — cross-provider syncedFrom preservation.
                // If the local row we're matching already has a `syncedFrom`
                // pointing at a DIFFERENT provider, this match fired because
                // the local is a push target for the current provider — its
                // pull source is elsewhere. Don't clobber syncedFrom and
                // don't refetch tracks (the source provider is authoritative).
                // Only the sync_playlist_link's syncedAt advances. Without
                // this guard, the original pull-source provider would see the
                // local as a new playlist on its next sync and create a
                // duplicate remote.
                val existingPullSource = if (existingBySpotifyId != null) {
                    syncPlaylistSourceDao.selectForLocal(existingBySpotifyId.id)
                } else null
                val isCrossProviderPushMirror = existingPullSource != null
                    && existingPullSource.providerId != providerId

                if (existingBySpotifyId != null && existingBySpotifyId.id != remote.entity.id) {
                    // Remove the old entry's tracks — they'll be replaced below
                    playlistTrackDao.deleteByPlaylistId(existingBySpotifyId.id)
                    playlistDao.delete(existingBySpotifyId)
                    // Clean up any orphaned sync source for the old ID
                    syncSourceDao.deleteAllForItem(existingBySpotifyId.id, "playlist")
                }

                if (isCrossProviderPushMirror) {
                    // Preserve the existing local row + tracks + syncedFrom.
                    // Just record that we synced with this provider as a
                    // push mirror by updating sync_playlist_link.syncedAt.
                    Log.d(TAG, "Cross-provider push mirror for ${existingBySpotifyId!!.id}: " +
                        "syncedFrom=${existingPullSource!!.providerId}; preserving local tracks")
                    syncPlaylistLinkDao.upsertWithSnapshot(
                        localPlaylistId = existingBySpotifyId.id,
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        snapshotId = remote.snapshotId,
                    )
                    syncSourceDao.insert(SyncSourceEntity(
                        itemId = existingBySpotifyId.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        syncedAt = System.currentTimeMillis(),
                    ))
                    unchanged++
                    continue
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
                // Standard pull-source path: record syncedFrom for this
                // provider so subsequent imports recognize it as own source
                // (and Phase 4's AM-import branch correctly identifies us
                // as a cross-provider push mirror at that point).
                syncPlaylistSourceDao.upsert(
                    localPlaylistId = targetId,
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    snapshotId = remote.snapshotId,
                    ownerId = null,
                    syncedAt = System.currentTimeMillis(),
                )
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
                        pushPlaylist(localPlaylist, spotifyProvider)
                        updated++
                    }
                    isHosted -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = System.currentTimeMillis()))
                        unchanged++
                    }
                    localModified && remoteSnapshotId != localSnapshotId -> {
                        val localIsNewer = localPlaylist.lastModified > existingSource.syncedAt
                        if (localIsNewer) {
                            pushPlaylist(localPlaylist, spotifyProvider)
                        } else {
                            pullPlaylist(localPlaylist, remote)
                        }
                        updated++
                    }
                    localModified -> {
                        pushPlaylist(localPlaylist, spotifyProvider)
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
                            val result = spotifyProvider.deletePlaylist(dupe.spotifyId)
                            when (result) {
                                is com.parachord.shared.sync.DeleteResult.Success ->
                                    deletedSpotifyIds.add(dupe.spotifyId)
                                is com.parachord.shared.sync.DeleteResult.Unsupported -> {
                                    Log.w(TAG, "Spotify deletePlaylist returned Unsupported(${result.status}); skipping")
                                    continue
                                }
                                is com.parachord.shared.sync.DeleteResult.Failed -> {
                                    Log.w(TAG, "Spotify deletePlaylist failed for ${dupe.spotifyId}", result.error)
                                    continue
                                }
                            }
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

        // ── Phase 4.5: per-provider push loop ────────────────────────
        // Iterate every enabled SyncProvider; for each, push the
        // playlists eligible for that provider (see `isPushCandidate`)
        // through the three-layer dedup + create-or-link logic.
        //
        // Spotify's existing behavior is preserved exactly — same
        // candidate filter (local-* and hosted-XSPF), same dedup, same
        // post-push row update. Apple Music adds a second iteration
        // when the user has it enabled in `enabled_sync_providers`;
        // for AM the candidate set ALSO includes Spotify-imported
        // playlists since the Phase 3 syncedFrom guard correctly skips
        // any playlist whose pull source matches the current push
        // target (Spotify-imported playlists' source IS Spotify, so
        // Spotify push skips them; AM push doesn't, so they propagate
        // into AM as expected).
        if (settings.pushLocalPlaylists) {
            val enabledProviderIds = settingsStore.getEnabledSyncProviders()
            val enabledProviders = providers.filter { it.id in enabledProviderIds }
            for (provider in enabledProviders) {
                // Per-provider axis opt-in — skip push for providers that
                // didn't enable the playlists axis in their wizard.
                if (!providerHasAxis(provider.id, "playlists")) continue
                // Spotify already loaded `remotePlaylists` + `deletedSpotifyIds`
                // above for the dedup-cleanup phase; reuse those for Spotify
                // and fetch fresh for any other provider.
                val providerRemote = if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
                    remotePlaylists
                } else {
                    try {
                        provider.fetchPlaylists(null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch ${provider.id} playlists for push pass", e)
                        continue
                    }
                }
                val deletedExternalIds = if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
                    deletedSpotifyIds
                } else emptySet()
                val pushed = pushPlaylistsForProvider(
                    provider = provider,
                    remotePlaylists = providerRemote,
                    deletedExternalIds = deletedExternalIds,
                )
                added += pushed
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

        // ── Phase 5: pull from non-Spotify enabled providers ─────────
        // The Spotify pull above is left inline because it's coupled
        // with the dedup-cleanup migration block (one-time Spotify-only
        // fix). Other providers (Apple Music today; Tidal-style future
        // ones) run through the helper, which mirrors the Spotify
        // import/cleanup body but parameterized on `provider`.
        val enabledProviderIds = settingsStore.getEnabledSyncProviders()
        val nonSpotifyProviders = providers.filter {
            it.id in enabledProviderIds && it.id != SpotifySyncProvider.PROVIDER_ID
        }
        for (provider in nonSpotifyProviders) {
            if (!providerHasAxis(provider.id, "playlists")) continue
            val pullResult = pullPlaylistsForProvider(provider, onProgress)
            added += pullResult.added
            removed += pullResult.removed
            updated += pullResult.updated
            unchanged += pullResult.unchanged
        }

        // Phase 3 — Fix 4 (multi-provider mirror propagation):
        // Clear `locallyModified` for any playlist whose relevant mirrors
        // are all caught up. `relevantMirrors` excludes the source
        // provider — the push loop never targets it (Fix 3 guard) so its
        // syncedAt would never advance and would strand the flag forever.
        // Phase 5 generalizes to all enabled providers (was Spotify-only).
        clearLocallyModifiedFlags(enabledProviderIds)

        return TypeSyncResult(added = added, removed = removed, updated = updated, unchanged = unchanged)
    }

    /**
     * Phase 5 — generic per-provider pull branch. Fetches the provider's
     * remote playlists, runs the import-or-update flow with the cross-
     * provider `syncedFrom` preservation guard, and cleans up sources
     * for remotes that have been deselected or deleted upstream.
     *
     * Mirrors the inline Spotify pull body in [syncPlaylists] but
     * parameterized on `provider` and stripped of the one-time dedup-
     * cleanup migration (Spotify-only by design).
     */
    private suspend fun pullPlaylistsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0

        val remotePlaylists = try {
            provider.fetchPlaylists { current, total ->
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, current, total, "Fetching ${provider.displayName} playlists..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlists from ${provider.displayName}", e)
            return TypeSyncResult()
        }

        val localSources = syncSourceDao.getByProvider(providerId, "playlist")
        val localByExternalId = localSources.associateBy { it.externalId }

        for ((index, remote) in remotePlaylists.withIndex()) {
            onProgress(SyncProgress(
                SyncPhase.PLAYLISTS,
                index + 1,
                remotePlaylists.size,
                "Syncing ${provider.displayName} playlist: ${remote.entity.name}",
            ))
            val existingSource = localByExternalId[remote.spotifyId]

            if (existingSource == null) {
                val now = System.currentTimeMillis()
                // The "merge into existing" path here used playlist.spotifyId
                // for Spotify; for other providers there's no equivalent
                // scalar — the link table is the lookup.
                val existingByLink = syncPlaylistLinkDao.selectByExternalId(providerId, remote.spotifyId)
                val existingLocalPlaylist = existingByLink?.let { playlistDao.getById(it.localPlaylistId) }
                val targetId = existingLocalPlaylist?.id ?: remote.entity.id

                // Phase 3 — cross-provider syncedFrom preservation:
                // if the local row's pull source points at a DIFFERENT
                // provider, this match fired because we're a push mirror
                // for that local. Don't refetch tracks; only update the
                // link's syncedAt + sync_source row.
                val existingPullSource = if (existingLocalPlaylist != null) {
                    syncPlaylistSourceDao.selectForLocal(existingLocalPlaylist.id)
                } else null
                val isCrossProviderPushMirror = existingPullSource != null
                    && existingPullSource.providerId != providerId

                if (existingLocalPlaylist != null && existingLocalPlaylist.id != remote.entity.id && !isCrossProviderPushMirror) {
                    playlistTrackDao.deleteByPlaylistId(existingLocalPlaylist.id)
                    playlistDao.delete(existingLocalPlaylist)
                    syncSourceDao.deleteAllForItem(existingLocalPlaylist.id, "playlist")
                }

                if (isCrossProviderPushMirror) {
                    Log.d(TAG, "Cross-provider push mirror for ${existingLocalPlaylist!!.id}: " +
                        "syncedFrom=${existingPullSource!!.providerId}; preserving local tracks")
                    syncPlaylistLinkDao.upsertWithSnapshot(
                        localPlaylistId = existingLocalPlaylist.id,
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        snapshotId = remote.snapshotId,
                    )
                    syncSourceDao.insert(SyncSourceEntity(
                        itemId = existingLocalPlaylist.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        syncedAt = System.currentTimeMillis(),
                    ))
                    unchanged++
                    continue
                }

                playlistDao.insert(remote.entity.copy(
                    id = targetId,
                    createdAt = existingLocalPlaylist?.createdAt ?: now,
                    updatedAt = now,
                    lastModified = now,
                ))
                val tracks = try {
                    provider.fetchPlaylistTracks(remote.spotifyId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch tracks for ${provider.displayName} playlist ${remote.spotifyId}", e)
                    emptyList()
                }
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
                syncPlaylistSourceDao.upsert(
                    localPlaylistId = targetId,
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    snapshotId = remote.snapshotId,
                    ownerId = null,
                    syncedAt = System.currentTimeMillis(),
                )
                syncPlaylistLinkDao.upsertWithSnapshot(
                    localPlaylistId = targetId,
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    snapshotId = remote.snapshotId,
                )
                added++
            } else {
                val localPlaylist = playlistDao.getById(existingSource.itemId)
                if (localPlaylist == null) {
                    unchanged++
                    continue
                }

                // Per-provider snapshot: read from the link table (NOT
                // from playlist.snapshotId, which is the Spotify-only
                // legacy scalar).
                val link = syncPlaylistLinkDao.selectForLink(localPlaylist.id, providerId)
                val remoteSnapshotId = remote.snapshotId
                val localSnapshotId = link?.snapshotId
                val localModified = localPlaylist.locallyModified
                val isHosted = localPlaylist.sourceUrl != null

                when {
                    isHosted && localModified -> {
                        pushPlaylist(localPlaylist, provider)
                        updated++
                    }
                    isHosted -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = System.currentTimeMillis()))
                        unchanged++
                    }
                    localModified && remoteSnapshotId != localSnapshotId -> {
                        val localIsNewer = localPlaylist.lastModified > existingSource.syncedAt
                        if (localIsNewer) {
                            pushPlaylist(localPlaylist, provider)
                        } else {
                            pullPlaylist(localPlaylist, remote, provider)
                        }
                        updated++
                    }
                    localModified -> {
                        pushPlaylist(localPlaylist, provider)
                        updated++
                    }
                    remoteSnapshotId != localSnapshotId -> {
                        pullPlaylist(localPlaylist, remote, provider)
                        updated++
                    }
                    else -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = System.currentTimeMillis()))
                        unchanged++
                    }
                }
            }
        }

        // Removed-source cleanup, mirroring the Spotify path. A locally-
        // owned playlist (hosted XSPF or local-*) is NEVER deleted by
        // sync cleanup — it's just deselected/deleted from the provider.
        val remoteIds = remotePlaylists.map { it.spotifyId }.toSet()
        val removedSources = localSources.filter { it.externalId != null && it.externalId !in remoteIds }
        removedSources.forEach { source ->
            val localPlaylist = playlistDao.getById(source.itemId)
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

    /**
     * Phase 4.5 — generic per-provider push branch. Iterates the
     * provider-aware candidates, runs three-layer dedup against the
     * provider's owned remote playlists, creates or links each one,
     * pushes the tracks (filtered to those that have the relevant
     * external ID), and records both the durable
     * `sync_playlist_link` row and the legacy `sync_source` row.
     *
     * Spotify-specific scalars (`playlists.spotifyId`,
     * `playlists.snapshotId`) are written ONLY when pushing to
     * Spotify — for any other provider, the link table is the
     * single source of truth (per Decision 5).
     *
     * Returns the number of new pushes (newly-linked or freshly
     * created remotes).
     */
    private suspend fun pushPlaylistsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        remotePlaylists: List<SyncedPlaylist>,
        deletedExternalIds: Set<String>,
    ): Int {
        val providerId = provider.id
        var added = 0

        val allPlaylists = playlistDao.getAllSync()
        val candidates = allPlaylists.filter {
            it.name.isNotBlank() && isPushCandidate(it, providerId)
        }

        val liveRemote = remotePlaylists.filter { it.spotifyId !in deletedExternalIds }
        val remoteById = liveRemote.associateBy { it.spotifyId }
        val ownedRemoteByName = liveRemote.filter { it.isOwned }
            .groupBy { it.entity.name.trim().lowercase() }

        for (playlist in candidates) {
            try {
                // Phase 3 — Fix 3 + pendingAction skip:
                // Skip rows the user marked remote-deleted; the playlist
                // detail banner will let the user re-push or unlink. Lift
                // the link lookup here so the dedup-Layer-1 block below
                // can reuse it without a second query.
                val link = syncPlaylistLinkDao.selectForLink(playlist.id, providerId)
                if (link?.pendingAction != null) {
                    Log.d(TAG, "Skipping push for ${playlist.id} on $providerId — pendingAction=${link.pendingAction}")
                    continue
                }

                // Phase 3 — Fix 3: provider-scoped `syncedFrom` guard.
                // Only skip when the CURRENT push target equals the
                // playlist's pull source — never blanket-skip on
                // `syncedFrom != null`. A Spotify-imported playlist
                // must remain pushable to Apple Music.
                val pullSource = syncPlaylistSourceDao.selectForLocal(playlist.id)
                if (pullSource?.providerId == providerId) {
                    Log.d(TAG, "Skipping push for ${playlist.id} on $providerId — its pull source")
                    continue
                }

                // Three-layer dedup — mirrors desktop's
                // sync:create-playlist IPC handler. Each layer yields an
                // `existing` remote we can link to instead of creating a
                // duplicate. Stop at the first hit; fall through to
                // create only if all three miss.

                // Layer 1: durable sync_playlist_link map.
                var existing: SyncedPlaylist? = null
                var matchSource = ""
                if (link != null) {
                    val fromLink = remoteById[link.externalId]
                    if (fromLink != null && fromLink.isOwned) {
                        existing = fromLink
                        matchSource = "id-link"
                    } else {
                        Log.d(TAG, "Stored link $providerId:${link.externalId} for ${playlist.id} no longer exists remotely; clearing")
                        syncPlaylistLinkDao.deleteForLink(playlist.id, providerId)
                    }
                }

                // Layer 2: playlist.spotifyId (Spotify-only convenience
                // cache; the candidate filter already excludes rows
                // with this set when iterating Spotify, so this only
                // ever matters as defense-in-depth).
                if (existing == null && providerId == SpotifySyncProvider.PROVIDER_ID && playlist.spotifyId != null) {
                    val fromField = remoteById[playlist.spotifyId]
                    if (fromField != null && fromField.isOwned) {
                        existing = fromField
                        matchSource = "playlist-field"
                    }
                }

                // Layer 3: name match (case-insensitive, trimmed, owned).
                if (existing == null) {
                    val nameCandidates = ownedRemoteByName[playlist.name.trim().lowercase()]
                    val pick = nameCandidates?.maxByOrNull { it.trackCount }
                    if (pick != null) {
                        existing = pick
                        matchSource = if ((nameCandidates.size) > 1)
                            "name-match (${nameCandidates.size} candidates)"
                        else "name-match"
                    }
                }

                val externalId: String
                val snapshotId: String?
                if (existing != null) {
                    Log.d(TAG, "Linked '${playlist.name}' to existing $providerId playlist ${existing.spotifyId} via $matchSource")
                    externalId = existing.spotifyId
                    snapshotId = existing.snapshotId
                } else {
                    val created = provider.createPlaylist(playlist.name, playlist.description)
                    externalId = created.externalId
                    snapshotId = created.snapshotId
                    Log.d(TAG, "Created $providerId playlist '${playlist.name}' ($externalId)")
                }

                // Write the durable link IMMEDIATELY — before track push
                // and before the playlist row update. If either of those
                // fails, the link still protects the next sync from
                // creating a fresh duplicate.
                syncPlaylistLinkDao.upsertWithSnapshot(
                    localPlaylistId = playlist.id,
                    providerId = providerId,
                    externalId = externalId,
                    snapshotId = snapshotId,
                )

                val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
                val externalTrackIds = extractExternalTrackIds(tracks, providerId)
                if (externalTrackIds.isNotEmpty()) {
                    provider.replacePlaylistTracks(externalId, externalTrackIds)
                }

                // Spotify-specific scalars stay write-through for backward
                // compat with code that still reads playlist.spotifyId
                // directly. For other providers, the link table is
                // authoritative (per Decision 5).
                if (providerId == SpotifySyncProvider.PROVIDER_ID) {
                    playlistDao.update(playlist.copy(
                        spotifyId = externalId,
                        snapshotId = snapshotId,
                        locallyModified = false,
                    ))
                } else {
                    playlistDao.update(playlist.copy(locallyModified = false))
                }
                syncSourceDao.insert(SyncSourceEntity(
                    itemId = playlist.id,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = externalId,
                    syncedAt = System.currentTimeMillis(),
                ))
                added++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push playlist ${playlist.name} to $providerId", e)
            }
        }
        return added
    }

    /**
     * Per-provider push-eligibility predicate. Spotify pushes only
     * locally-created (`local-*`) and hosted-XSPF playlists, matching
     * legacy behavior — and excludes rows that are already linked
     * (`spotifyId != null`). Apple Music has the same baseline plus
     * Spotify-imported playlists (`spotify-*`), since the Phase 3
     * `syncedFrom` guard correctly skips a Spotify-imported playlist
     * when targeting Spotify but not when targeting Apple Music.
     */
    private fun isPushCandidate(playlist: PlaylistEntity, providerId: String): Boolean {
        val baseEligible = playlist.id.startsWith("local-") || playlist.sourceUrl != null
        return when (providerId) {
            SpotifySyncProvider.PROVIDER_ID ->
                playlist.spotifyId == null && baseEligible
            AppleMusicSyncProvider.PROVIDER_ID ->
                baseEligible || playlist.id.startsWith("spotify-")
            else -> baseEligible
        }
    }

    /**
     * Extracts the per-provider external IDs from a playlist's tracks.
     * Tracks without the relevant ID are silently skipped — Phase 4
     * defers catalog-search-based ID resolution, so Apple Music push
     * only handles tracks that already have an `appleMusicId`.
     */
    private fun extractExternalTrackIds(
        tracks: List<PlaylistTrackEntity>,
        providerId: String,
    ): List<String> = when (providerId) {
        SpotifySyncProvider.PROVIDER_ID -> tracks.mapNotNull { it.trackSpotifyUri }
        AppleMusicSyncProvider.PROVIDER_ID -> tracks.mapNotNull { it.trackAppleMusicId }
        else -> emptyList()
    }

    /**
     * Push a locally-modified playlist's tracks to a previously-linked
     * remote on [provider]. Used by the import-branch's update flow
     * when the local rev is newer than the remote.
     *
     * Resolves the external ID from `sync_playlist_link` for the
     * provider; falls back to the legacy `playlist.spotifyId` scalar
     * only for Spotify (backward-compat with installs that predate
     * Phase 1's link table). For any other provider, no link → no push.
     */
    private suspend fun pushPlaylist(
        playlist: PlaylistEntity,
        provider: com.parachord.shared.sync.SyncProvider,
    ) {
        val link = syncPlaylistLinkDao.selectForLink(playlist.id, provider.id)
        // Resolve the external id: link table is authoritative; legacy
        // playlist.spotifyId is a fallback for Spotify only (backward
        // compat with installs that predate Phase 1's link table).
        val externalIdNullable: String? = link?.externalId
            ?: if (provider.id == SpotifySyncProvider.PROVIDER_ID) playlist.spotifyId else null
        val externalId: String = externalIdNullable ?: run {
            Log.w(TAG, "pushPlaylist: no link for ${playlist.id} on ${provider.id}; skip")
            return
        }
        val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
        val externalTrackIds = extractExternalTrackIds(tracks, provider.id)
        val snapshotId = provider.replacePlaylistTracks(externalId, externalTrackIds)

        // Spotify-specific scalars stay write-through for backward compat
        // (per Decision 5). For other providers, only the link's
        // snapshot column is the source of truth.
        if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
            playlistDao.update(playlist.copy(
                snapshotId = snapshotId ?: playlist.snapshotId,
                trackCount = tracks.size,
                locallyModified = false,
            ))
        } else {
            playlistDao.update(playlist.copy(
                trackCount = tracks.size,
                locallyModified = false,
            ))
        }
        if (snapshotId != null) {
            syncPlaylistLinkDao.upsertWithSnapshot(
                localPlaylistId = playlist.id,
                providerId = provider.id,
                externalId = externalId,
                snapshotId = snapshotId,
            )
        }

        val source = syncSourceDao.get(playlist.id, "playlist", provider.id)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun pullPlaylist(
        localPlaylist: PlaylistEntity,
        remote: SyncedPlaylist,
        provider: com.parachord.shared.sync.SyncProvider = spotifyProvider,
    ) {
        // Fetch tracks first, then update playlist metadata with actual count
        // (use update, not insert/REPLACE, because REPLACE does DELETE+INSERT
        // which CASCADE-deletes playlist_tracks)
        val tracks = provider.fetchPlaylistTracks(remote.spotifyId)
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
            localPlaylist.id, provider.id,
        )
        // Spotify-specific scalars stay write-through (per Decision 5).
        // For other providers, sync_playlist_link.snapshotId is the
        // source of truth and gets refreshed via the upsert below.
        // Preserve locally-generated mosaic art (`file://...filesDir/playlist_mosaics/`)
        // from being overwritten by the provider's stock playlist art. Mosaics are
        // built from album-art tiles in ImageEnrichmentService and are the canonical
        // visual identity for playlists in Parachord. The provider's art gets used
        // only when we have no local mosaic yet.
        val resolvedArtwork = preserveLocalMosaic(localPlaylist.artworkUrl, remote.entity.artworkUrl)
        if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
            playlistDao.update(localPlaylist.copy(
                name = remote.entity.name,
                description = remote.entity.description,
                artworkUrl = resolvedArtwork,
                trackCount = tracks.size,
                snapshotId = remote.snapshotId,
                updatedAt = now,
                lastModified = now,
                locallyModified = hasOtherMirrors,
                ownerName = remote.entity.ownerName,
            ))
        } else {
            playlistDao.update(localPlaylist.copy(
                name = remote.entity.name,
                description = remote.entity.description,
                artworkUrl = resolvedArtwork,
                trackCount = tracks.size,
                updatedAt = now,
                lastModified = now,
                locallyModified = hasOtherMirrors,
                ownerName = remote.entity.ownerName,
            ))
        }
        // Refresh the link snapshot for this provider — the per-provider
        // pull-vs-push decision in the next sync cycle reads from here
        // (not from the legacy playlists.snapshotId scalar).
        syncPlaylistLinkDao.upsertWithSnapshot(
            localPlaylistId = localPlaylist.id,
            providerId = provider.id,
            externalId = remote.spotifyId,
            snapshotId = remote.snapshotId,
        )

        val source = syncSourceDao.get(localPlaylist.id, "playlist", provider.id)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = System.currentTimeMillis()))
        }
    }

    // ── Bidirectional removal ────────────────────────────────────

    suspend fun onTrackRemoved(track: TrackEntity) {
        val sources = syncSourceDao.getByItem(track.id, "track")
        val providersById = providers.associateBy { it.id }
        for (source in sources) {
            val externalId = source.externalId ?: continue
            val provider = providersById[source.providerId] ?: continue
            try {
                provider.removeTracks(listOf(externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove track from ${provider.displayName}", e)
            }
        }
        syncSourceDao.deleteAllForItem(track.id, "track")
    }

    suspend fun onAlbumRemoved(album: AlbumEntity) {
        val sources = syncSourceDao.getByItem(album.id, "album")
        val providersById = providers.associateBy { it.id }
        for (source in sources) {
            val externalId = source.externalId ?: continue
            val provider = providersById[source.providerId] ?: continue
            try {
                provider.removeAlbums(listOf(externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove album from ${provider.displayName}", e)
            }
        }
        syncSourceDao.deleteAllForItem(album.id, "album")
    }

    suspend fun onArtistRemoved(artist: ArtistEntity) {
        val sources = syncSourceDao.getByItem(artist.id, "artist")
        val providersById = providers.associateBy { it.id }
        for (source in sources) {
            val externalId = source.externalId ?: continue
            val provider = providersById[source.providerId] ?: continue
            try {
                provider.unfollowArtists(listOf(externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unfollow artist on ${provider.displayName}", e)
            }
        }
        syncSourceDao.deleteAllForItem(artist.id, "artist")
    }

    /**
     * Per-provider result of attempting to delete a playlist's remote
     * mirror. Surfaced to the UI so the user can be told when an Apple
     * Music mirror couldn't be deleted via the API (Decision D8) — the
     * playlist still exists in Apple Music and the user has to remove
     * it from the Music app manually.
     */
    data class PlaylistDeletionAttempt(
        val providerId: String,
        val providerDisplayName: String,
        val externalId: String,
        val result: com.parachord.shared.sync.DeleteResult,
    )

    /**
     * Phase 6.5 — sync-aware playlist deletion. Iterates the playlist's
     * `sync_playlist_link` rows and calls each provider's
     * [com.parachord.shared.sync.SyncProvider.deletePlaylist], returning
     * the per-provider results. Cleanup of the local link rows + sync
     * sources happens regardless of provider response so the local
     * state stays consistent — if Apple Music returns Unsupported, we
     * unlink Parachord-side and surface the limitation to the user.
     *
     * Caller is responsible for the actual local row deletion
     * (`playlistDao.delete`) — this method only handles the remote
     * cleanup so it composes with `LibraryRepository.deletePlaylist`
     * without ordering surprises.
     */
    suspend fun onPlaylistRemoved(playlist: PlaylistEntity): List<PlaylistDeletionAttempt> {
        val attempts = mutableListOf<PlaylistDeletionAttempt>()
        val links = syncPlaylistLinkDao.selectForLocal(playlist.id)
        for (link in links) {
            val provider = providers.firstOrNull { it.id == link.providerId } ?: continue
            val result = try {
                provider.deletePlaylist(link.externalId)
            } catch (e: Exception) {
                Log.e(TAG, "deletePlaylist threw for ${provider.id}:${link.externalId}", e)
                com.parachord.shared.sync.DeleteResult.Failed(e)
            }
            attempts.add(PlaylistDeletionAttempt(
                providerId = provider.id,
                providerDisplayName = provider.displayName,
                externalId = link.externalId,
                result = result,
            ))
            // Always clean up the local link + sync source — even when
            // the remote returned Unsupported, the user's intent is
            // "stop syncing this playlist." Leaving the link would
            // re-link on next sync via three-layer dedup.
            syncPlaylistLinkDao.deleteForLink(playlist.id, provider.id)
            syncSourceDao.deleteByKey(playlist.id, "playlist", provider.id)
        }
        // Drop the syncedFrom row too, in case this was a pull-source
        // playlist (avoids the next sync re-importing it).
        syncPlaylistSourceDao.deleteForLocal(playlist.id)
        return attempts
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

    /**
     * Don't overwrite a locally-generated mosaic with the provider's
     * stock playlist art. Mosaics live under `filesDir/playlist_mosaics/`
     * and are written as `file://...` URIs by [com.parachord.android.data.metadata.ImageEnrichmentService.enrichPlaylistArt].
     * Returns:
     *  - the existing mosaic when it's a `file://` path (don't overwrite)
     *  - the remote URL when there's no existing artwork
     *  - the existing remote URL when both are remote (no-op churn)
     */
    private fun preserveLocalMosaic(existing: String?, remote: String?): String? {
        if (existing != null && existing.startsWith("file://")) return existing
        return remote ?: existing
    }
}
