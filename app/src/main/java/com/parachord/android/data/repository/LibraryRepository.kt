package com.parachord.android.data.repository

import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
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

    // ── Playlist tracks ─────────────────────────────────────────────

    fun getPlaylistTracks(playlistId: String): Flow<List<PlaylistTrackEntity>> =
        playlistTrackDao.getByPlaylistId(playlistId)

    /**
     * Create a playlist with its tracks in one operation.
     * Tracks are stored in the playlist_tracks junction table, NOT in the
     * Collection tracks table.
     */
    suspend fun createPlaylistWithTracks(
        playlist: PlaylistEntity,
        tracks: List<TrackEntity>,
    ) {
        playlistDao.insert(playlist)
        val playlistTracks = tracks.mapIndexed { index, track ->
            PlaylistTrackEntity(
                playlistId = playlist.id,
                position = index,
                trackTitle = track.title,
                trackArtist = track.artist,
                trackAlbum = track.album,
                trackDuration = track.duration,
                trackArtworkUrl = track.artworkUrl,
                trackSourceUrl = track.sourceUrl,
                trackResolver = track.resolver,
                trackSpotifyUri = track.spotifyUri,
                trackSoundcloudId = track.soundcloudId,
            )
        }
        playlistTrackDao.insertAll(playlistTracks)
    }

    /** Convert a PlaylistTrackEntity to a TrackEntity for playback. */
    fun playlistTrackToTrackEntity(pt: PlaylistTrackEntity): TrackEntity =
        TrackEntity(
            id = UUID.randomUUID().toString(),
            title = pt.trackTitle,
            artist = pt.trackArtist,
            album = pt.trackAlbum,
            duration = pt.trackDuration,
            artworkUrl = pt.trackArtworkUrl,
            sourceUrl = pt.trackSourceUrl,
            resolver = pt.trackResolver,
            spotifyUri = pt.trackSpotifyUri,
            soundcloudId = pt.trackSoundcloudId,
        )
}
