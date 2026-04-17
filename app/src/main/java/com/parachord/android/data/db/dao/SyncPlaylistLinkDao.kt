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
    )

    suspend fun selectAll(): List<Link> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList().map {
            Link(it.localPlaylistId, it.providerId, it.externalId, it.syncedAt)
        }
    }

    suspend fun selectForLink(localPlaylistId: String, providerId: String): Link? =
        withContext(Dispatchers.IO) {
            queries.selectForLink(localPlaylistId, providerId).executeAsOneOrNull()?.let {
                Link(it.localPlaylistId, it.providerId, it.externalId, it.syncedAt)
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
