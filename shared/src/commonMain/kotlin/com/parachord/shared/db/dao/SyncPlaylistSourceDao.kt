package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight SyncPlaylistSourceQueries.
 *
 * Represents the per-playlist `syncedFrom` pull source (what provider + remote
 * this local playlist was imported from). Single source per playlist — keyed
 * on localPlaylistId alone, distinct from SyncPlaylistLinkDao which is keyed
 * (localPlaylistId, providerId) for push targets.
 */
class SyncPlaylistSourceDao(private val db: ParachordDb) {

    private val queries get() = db.syncPlaylistSourceQueries

    data class Source(
        val localPlaylistId: String,
        val providerId: String,
        val externalId: String,
        val snapshotId: String?,
        val ownerId: String?,
        val syncedAt: Long,
    )

    suspend fun selectAll(): List<Source> = withContext(Dispatchers.Default) {
        queries.selectAll().executeAsList().map { it.toSource() }
    }

    suspend fun selectForLocal(localPlaylistId: String): Source? = withContext(Dispatchers.Default) {
        queries.selectForLocal(localPlaylistId).executeAsOneOrNull()?.toSource()
    }

    suspend fun selectByExternalId(providerId: String, externalId: String): Source? =
        withContext(Dispatchers.Default) {
            queries.selectByExternalId(providerId, externalId).executeAsOneOrNull()?.toSource()
        }

    suspend fun upsert(
        localPlaylistId: String,
        providerId: String,
        externalId: String,
        snapshotId: String?,
        ownerId: String?,
        syncedAt: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.upsert(localPlaylistId, providerId, externalId, snapshotId, ownerId, syncedAt)
    }

    suspend fun deleteForLocal(localPlaylistId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForLocal(localPlaylistId)
    }

    suspend fun deleteForProvider(providerId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForProvider(providerId)
    }

    private fun com.parachord.shared.db.Sync_playlist_source.toSource() = Source(
        localPlaylistId = localPlaylistId,
        providerId = providerId,
        externalId = externalId,
        snapshotId = snapshotId,
        ownerId = ownerId,
        syncedAt = syncedAt,
    )
}
