package com.parachord.android.data.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.parachord.shared.db.Artists
import com.parachord.shared.db.ParachordDb
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.shared.model.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [ArtistQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class ArtistDao(private val db: ParachordDb) {

    private val queries get() = db.artistQueries

    /* ---- Mapping ---- */

    private fun Artists.toArtist() = Artist(
        id = id,
        name = name,
        imageUrl = imageUrl,
        spotifyId = spotifyId,
        genres = genres,
        addedAt = addedAt,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<ArtistEntity>> =
        queries.getAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toArtist() } }

    fun getByName(name: String): Flow<ArtistEntity?> =
        queries.getByName(name).asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.toArtist() }

    fun existsByName(name: String): Flow<Boolean> =
        queries.existsByName(name).asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it ?: false }

    fun search(query: String): Flow<List<ArtistEntity>> =
        queries.search(query).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toArtist() } }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): ArtistEntity? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toArtist()
    }

    suspend fun getBySpotifyId(spotifyId: String): ArtistEntity? = withContext(Dispatchers.IO) {
        queries.getBySpotifyId(spotifyId).executeAsOneOrNull()?.toArtist()
    }

    /* ---- Writes ---- */

    suspend fun insert(artist: ArtistEntity): Unit = withContext(Dispatchers.IO) {
        queries.insert(
            id = artist.id,
            name = artist.name,
            imageUrl = artist.imageUrl,
            spotifyId = artist.spotifyId,
            genres = artist.genres,
            addedAt = artist.addedAt,
        )
    }

    suspend fun insertAll(artists: List<ArtistEntity>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            for (artist in artists) {
                queries.insert(
                    id = artist.id,
                    name = artist.name,
                    imageUrl = artist.imageUrl,
                    spotifyId = artist.spotifyId,
                    genres = artist.genres,
                    addedAt = artist.addedAt,
                )
            }
        }
    }

    suspend fun delete(artist: ArtistEntity): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(artist.id)
    }

    suspend fun deleteById(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }

    suspend fun deleteByName(name: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteByName(name)
    }

    suspend fun updateImageByName(name: String, imageUrl: String): Unit = withContext(Dispatchers.IO) {
        queries.updateImageByName(imageUrl = imageUrl, name = name)
    }
}
