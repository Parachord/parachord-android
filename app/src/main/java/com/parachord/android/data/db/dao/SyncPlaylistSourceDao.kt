package com.parachord.android.data.db.dao

import com.parachord.shared.db.ParachordDb
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

    suspend fun selectAll(): List<Source> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList().map { it.toSource() }
    }

    suspend fun selectForLocal(localPlaylistId: String): Source? = withContext(Dispatchers.IO) {
        queries.selectForLocal(localPlaylistId).executeAsOneOrNull()?.toSource()
    }

    suspend fun selectByExternalId(providerId: String, externalId: String): Source? =
        withContext(Dispatchers.IO) {
            queries.selectByExternalId(providerId, externalId).executeAsOneOrNull()?.toSource()
        }

    suspend fun upsert(
        localPlaylistId: String,
        providerId: String,
        externalId: String,
        snapshotId: String?,
        ownerId: String?,
        syncedAt: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        queries.upsert(localPlaylistId, providerId, externalId, snapshotId, ownerId, syncedAt)
    }

    suspend fun deleteForLocal(localPlaylistId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteForLocal(localPlaylistId)
    }

    suspend fun deleteForProvider(providerId: String): Unit = withContext(Dispatchers.IO) {
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
