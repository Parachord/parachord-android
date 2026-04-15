package com.parachord.android.data.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Playlists
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.shared.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [PlaylistQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class PlaylistDao(private val db: ParachordDb) {

    private val queries get() = db.playlistQueries

    /* ---- Mapping ---- */

    private fun Playlists.toPlaylist() = Playlist(
        id = id,
        name = name,
        description = description,
        artworkUrl = artworkUrl,
        trackCount = trackCount.toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        spotifyId = spotifyId,
        snapshotId = snapshotId,
        lastModified = lastModified,
        locallyModified = locallyModified != 0L,
        ownerName = ownerName,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<PlaylistEntity>> =
        queries.getAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toPlaylist() } }

    fun getByIdFlow(id: String): Flow<PlaylistEntity?> =
        queries.getById(id).asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.toPlaylist() }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): PlaylistEntity? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toPlaylist()
    }

    suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity? = withContext(Dispatchers.IO) {
        queries.getBySpotifyId(spotifyId).executeAsOneOrNull()?.toPlaylist()
    }

    suspend fun getAllSync(): List<PlaylistEntity> = withContext(Dispatchers.IO) {
        queries.getAll().executeAsList().map { it.toPlaylist() }
    }

    /* ---- Writes ---- */

    suspend fun insert(playlist: PlaylistEntity): Unit = withContext(Dispatchers.IO) {
        queries.insert(
            id = playlist.id,
            name = playlist.name,
            description = playlist.description,
            artworkUrl = playlist.artworkUrl,
            trackCount = playlist.trackCount.toLong(),
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
            spotifyId = playlist.spotifyId,
            snapshotId = playlist.snapshotId,
            lastModified = playlist.lastModified,
            locallyModified = if (playlist.locallyModified) 1L else 0L,
            ownerName = playlist.ownerName,
        )
    }

    /** INSERT OR REPLACE — same as insert since the .sq uses INSERT OR REPLACE. */
    suspend fun update(playlist: PlaylistEntity): Unit = insert(playlist)

    suspend fun updateArtworkById(id: String, artworkUrl: String): Unit = withContext(Dispatchers.IO) {
        queries.updateArtworkById(artworkUrl = artworkUrl, id = id)
    }

    suspend fun backfillLastModified(): Unit = withContext(Dispatchers.IO) {
        queries.backfillLastModified()
    }

    suspend fun delete(playlist: PlaylistEntity): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(playlist.id)
    }
}
