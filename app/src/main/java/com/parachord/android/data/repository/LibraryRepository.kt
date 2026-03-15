package com.parachord.android.data.repository

import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.ArtistDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncEngine: SyncEngine,
) {
    fun getAllTracks(): Flow<List<TrackEntity>> = trackDao.getAll()
    fun getAllAlbums(): Flow<List<AlbumEntity>> = albumDao.getAll()
    fun getAllArtists(): Flow<List<ArtistEntity>> = artistDao.getAll()
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAll()

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.search(query)
    fun searchAlbums(query: String): Flow<List<AlbumEntity>> = albumDao.search(query)

    fun getAlbumTracks(albumId: String): Flow<List<TrackEntity>> = trackDao.getByAlbumId(albumId)

    suspend fun getAlbumByTitleAndArtist(title: String, artist: String): AlbumEntity? =
        albumDao.getByTitleAndArtist(title, artist)

    suspend fun addTrack(track: TrackEntity) {
        val existing = trackDao.getById(track.id)
        // Preserve the synced addedAt timestamp if one exists
        trackDao.insert(if (existing != null) track.copy(addedAt = existing.addedAt) else track)
    }
    suspend fun addTracks(tracks: List<TrackEntity>) = trackDao.insertAll(tracks)
    suspend fun addAlbum(album: AlbumEntity) = albumDao.insert(album)
    suspend fun addAlbums(albums: List<AlbumEntity>) = albumDao.insertAll(albums)
    suspend fun addArtist(artist: ArtistEntity) = artistDao.insert(artist)
    suspend fun addArtists(artists: List<ArtistEntity>) = artistDao.insertAll(artists)
    suspend fun addPlaylist(playlist: PlaylistEntity) = playlistDao.insert(playlist)

    /** Backfill lastModified from updatedAt for playlists synced before tracking. */
    suspend fun backfillPlaylistLastModified() = playlistDao.backfillLastModified()

    /** Reactive check whether a track exists in collection by title+artist. */
    fun isTrackInCollection(title: String, artist: String): Flow<Boolean> =
        trackDao.existsByTitleAndArtist(title, artist)

    suspend fun deleteTrack(track: TrackEntity) = trackDao.delete(track)
    suspend fun deleteAlbum(album: AlbumEntity) = albumDao.delete(album)
    suspend fun deletePlaylist(playlist: PlaylistEntity) = playlistDao.delete(playlist)

    // ── Sync-aware deletions (push removal back to source) ───────────

    suspend fun deleteTrackWithSync(track: TrackEntity) {
        syncEngine.onTrackRemoved(track)
        trackDao.delete(track)
    }

    suspend fun deleteAlbumWithSync(album: AlbumEntity) {
        syncEngine.onAlbumRemoved(album)
        albumDao.delete(album)
    }

    suspend fun deleteArtistWithSync(artist: ArtistEntity) {
        syncEngine.onArtistRemoved(artist)
        artistDao.delete(artist)
    }

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
                trackSpotifyId = track.spotifyId,
                trackAppleMusicId = track.appleMusicId,
            )
        }
        playlistTrackDao.insertAll(playlistTracks)
    }

    /**
     * Append tracks to an existing playlist.
     * Positions are assigned after the current max position.
     */
    suspend fun addTracksToPlaylist(playlistId: String, tracks: List<TrackEntity>) {
        val startPosition = playlistTrackDao.getMaxPosition(playlistId) + 1
        val playlistTracks = tracks.mapIndexed { index, track ->
            PlaylistTrackEntity(
                playlistId = playlistId,
                position = startPosition + index,
                trackTitle = track.title,
                trackArtist = track.artist,
                trackAlbum = track.album,
                trackDuration = track.duration,
                trackArtworkUrl = track.artworkUrl,
                trackSourceUrl = track.sourceUrl,
                trackResolver = track.resolver,
                trackSpotifyUri = track.spotifyUri,
                trackSoundcloudId = track.soundcloudId,
                trackSpotifyId = track.spotifyId,
                trackAppleMusicId = track.appleMusicId,
            )
        }
        playlistTrackDao.insertAll(playlistTracks)
        // Update track count and modification timestamps
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = System.currentTimeMillis()
        playlistDao.update(playlist.copy(
            trackCount = playlist.trackCount + tracks.size,
            updatedAt = now,
            lastModified = now,
            locallyModified = true,
        ))
    }

    /** Remove a single track from a playlist by position and update the count. */
    suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) {
        playlistTrackDao.deleteTrack(playlistId, position)
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = System.currentTimeMillis()
        playlistDao.update(playlist.copy(
            trackCount = (playlist.trackCount - 1).coerceAtLeast(0),
            updatedAt = now,
            lastModified = now,
            locallyModified = true,
        ))
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
            spotifyId = pt.trackSpotifyId,
            soundcloudId = pt.trackSoundcloudId,
            appleMusicId = pt.trackAppleMusicId,
        )
}
