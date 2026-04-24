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
        queries.selectAll().executeAsList().map {
            Link(
                localPlaylistId = it.localPlaylistId,
                providerId = it.providerId,
                externalId = it.externalId,
                syncedAt = it.syncedAt,
                snapshotId = it.snapshotId,
                pendingAction = it.pendingAction,
            )
        }
    }

    suspend fun selectForLink(localPlaylistId: String, providerId: String): Link? =
        withContext(Dispatchers.IO) {
            queries.selectForLink(localPlaylistId, providerId).executeAsOneOrNull()?.let {
                Link(
                    localPlaylistId = it.localPlaylistId,
                    providerId = it.providerId,
                    externalId = it.externalId,
                    syncedAt = it.syncedAt,
                    snapshotId = it.snapshotId,
                    pendingAction = it.pendingAction,
                )
            }
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
            queries.selectPendingForProvider(providerId).executeAsList().map {
                Link(
                    localPlaylistId = it.localPlaylistId,
                    providerId = it.providerId,
                    externalId = it.externalId,
                    syncedAt = it.syncedAt,
                    snapshotId = it.snapshotId,
                    pendingAction = it.pendingAction,
                )
            }
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
}
