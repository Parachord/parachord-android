package com.parachord.android.data.repository

import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
) {
    fun getAllTracks(): Flow<List<TrackEntity>> = trackDao.getAll()
    fun getAllAlbums(): Flow<List<AlbumEntity>> = albumDao.getAll()
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAll()

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.search(query)
    fun searchAlbums(query: String): Flow<List<AlbumEntity>> = albumDao.search(query)

    fun getAlbumTracks(albumId: String): Flow<List<TrackEntity>> = trackDao.getByAlbumId(albumId)

    suspend fun addTrack(track: TrackEntity) = trackDao.insert(track)
    suspend fun addTracks(tracks: List<TrackEntity>) = trackDao.insertAll(tracks)
    suspend fun addAlbum(album: AlbumEntity) = albumDao.insert(album)
    suspend fun addAlbums(albums: List<AlbumEntity>) = albumDao.insertAll(albums)
    suspend fun addPlaylist(playlist: PlaylistEntity) = playlistDao.insert(playlist)

    suspend fun deleteTrack(track: TrackEntity) = trackDao.delete(track)
    suspend fun deleteAlbum(album: AlbumEntity) = albumDao.delete(album)
    suspend fun deletePlaylist(playlist: PlaylistEntity) = playlistDao.delete(playlist)
}
