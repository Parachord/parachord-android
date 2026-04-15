package com.parachord.android.data.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.parachord.shared.db.Albums
import com.parachord.shared.db.ParachordDb
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.shared.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [AlbumQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class AlbumDao(private val db: ParachordDb) {

    private val queries get() = db.albumQueries

    /* ---- Mapping ---- */

    private fun Albums.toAlbum() = Album(
        id = id,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        year = year?.toInt(),
        trackCount = trackCount?.toInt(),
        addedAt = addedAt,
        spotifyId = spotifyId,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<AlbumEntity>> =
        queries.getAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toAlbum() } }

    fun search(query: String): Flow<List<AlbumEntity>> =
        queries.search(query, query).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toAlbum() } }

    fun existsByTitleAndArtist(title: String, artist: String): Flow<Boolean> =
        queries.existsByTitleAndArtist(title, artist).asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it ?: false }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): AlbumEntity? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toAlbum()
    }

    suspend fun getByTitleAndArtist(title: String, artist: String): AlbumEntity? = withContext(Dispatchers.IO) {
        queries.getByTitleAndArtist(title, artist).executeAsOneOrNull()?.toAlbum()
    }

    /* ---- Writes ---- */

    suspend fun insert(album: AlbumEntity): Unit = withContext(Dispatchers.IO) {
        queries.insert(
            id = album.id,
            title = album.title,
            artist = album.artist,
            artworkUrl = album.artworkUrl,
            year = album.year?.toLong(),
            trackCount = album.trackCount?.toLong(),
            addedAt = album.addedAt,
            spotifyId = album.spotifyId,
        )
    }

    suspend fun insertAll(albums: List<AlbumEntity>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            for (album in albums) {
                queries.insert(
                    id = album.id,
                    title = album.title,
                    artist = album.artist,
                    artworkUrl = album.artworkUrl,
                    year = album.year?.toLong(),
                    trackCount = album.trackCount?.toLong(),
                    addedAt = album.addedAt,
                    spotifyId = album.spotifyId,
                )
            }
        }
    }

    suspend fun delete(album: AlbumEntity): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(album.id)
    }

    suspend fun updateArtworkByTitleAndArtist(title: String, artist: String, artworkUrl: String): Unit =
        withContext(Dispatchers.IO) {
            queries.updateArtworkByTitleAndArtist(
                artworkUrl = artworkUrl,
                title = title,
                artist = artist,
            )
        }
}
