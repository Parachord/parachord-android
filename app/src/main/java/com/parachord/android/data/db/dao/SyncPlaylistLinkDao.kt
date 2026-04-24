package com.parachord.android.data.db.dao

import com.parachord.shared.db.ParachordDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable local→remote playlist ID map, stored independently of the playlists
 * table so a playlist-save that forgets to forward `spotifyId` cannot clobber
 * it. Read before creating a remote playlist to avoid duplicates; written
 * immediately after a successful create or link.
 *
 * Mirrors the desktop `sync_playlist_links` electron-store entry.
 */
class SyncPlaylistLinkDao(private val db: ParachordDb) {

    private val queries get() = db.syncPlaylistLinkQueries

    data class Link(
        val localPlaylistId: String,
        val providerId: String,
        val externalId: String,
        val syncedAt: Long,
        val snapshotId: String? = null,
        val pendingAction: String? = null,
    )

    suspend fun selectAll(): List<Link> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList().map { it.toLink() }
    }

    suspend fun selectForLink(localPlaylistId: String, providerId: String): Link? =
        withContext(Dispatchers.IO) {
            queries.selectForLink(localPlaylistId, providerId).executeAsOneOrNull()?.toLink()
        }

    suspend fun upsert(
        localPlaylistId: String,
        providerId: String,
        externalId: String,
        syncedAt: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        queries.upsert(localPlaylistId, providerId, externalId, syncedAt)
    }

    suspend fun upsertWithSnapshot(
        localPlaylistId: String,
        providerId: String,
        externalId: String,
        snapshotId: String?,
        syncedAt: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        queries.upsertWithSnapshot(localPlaylistId, providerId, externalId, snapshotId, syncedAt)
    }

    /**
     * DAO signature is keys-first (localPlaylistId, providerId, pendingAction) to match
     * sibling methods like selectForLink/deleteForLink. The SQLDelight-generated
     * queries.setPendingAction binds in SQL order (pendingAction, localPlaylistId, providerId);
     * the rebinding below is intentional — do NOT "fix" the apparent mismatch.
     */
    suspend fun setPendingAction(
        localPlaylistId: String,
        providerId: String,
        pendingAction: String?,
    ): Unit = withContext(Dispatchers.IO) {
        queries.setPendingAction(pendingAction, localPlaylistId, providerId)
    }

    suspend fun clearPendingAction(localPlaylistId: String, providerId: String): Unit =
        withContext(Dispatchers.IO) {
            queries.clearPendingAction(localPlaylistId, providerId)
        }

    suspend fun selectPendingForProvider(providerId: String): List<Link> =
        withContext(Dispatchers.IO) {
            queries.selectPendingForProvider(providerId).executeAsList().map { it.toLink() }
        }

    suspend fun deleteForLink(localPlaylistId: String, providerId: String): Unit =
        withContext(Dispatchers.IO) {
            queries.deleteForLink(localPlaylistId, providerId)
        }

    suspend fun deleteByExternalId(providerId: String, externalId: String): Unit =
        withContext(Dispatchers.IO) {
            queries.deleteByExternalId(providerId, externalId)
        }

    suspend fun deleteForLocal(localPlaylistId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteForLocal(localPlaylistId)
    }

    suspend fun deleteForProvider(providerId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteForProvider(providerId)
    }

    private fun com.parachord.shared.db.Sync_playlist_link.toLink() = Link(
        localPlaylistId = localPlaylistId,
        providerId = providerId,
        externalId = externalId,
        snapshotId = snapshotId,
        pendingAction = pendingAction,
        syncedAt = syncedAt,
    )

    /**
     * `selectPendingForProvider` filters on `pendingAction IS NOT NULL`, so
     * SQLDelight generates a bespoke `SelectPendingForProvider` row type with
     * `pendingAction: String` (non-null) instead of reusing `Sync_playlist_link`.
     * Widen back to the nullable `Link.pendingAction` so callers see a single type.
     */
    private fun com.parachord.shared.db.SelectPendingForProvider.toLink() = Link(
        localPlaylistId = localPlaylistId,
        providerId = providerId,
        externalId = externalId,
        snapshotId = snapshotId,
        pendingAction = pendingAction,
        syncedAt = syncedAt,
    )
}
