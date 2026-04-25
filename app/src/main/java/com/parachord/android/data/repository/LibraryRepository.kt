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
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.metadata.TrackEnrichmentRequest
import com.parachord.android.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class LibraryRepository constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncEngine: SyncEngine,
    private val mbidEnrichment: MbidEnrichmentService,
    private val syncPlaylistLinkDao: com.parachord.android.data.db.dao.SyncPlaylistLinkDao,
    private val syncPlaylistSourceDao: com.parachord.android.data.db.dao.SyncPlaylistSourceDao,
) {

    /**
     * True iff the playlist has any sync intent — either a `syncedFrom`
     * pull source OR at least one `syncedTo` push mirror. Local-only
     * playlists return false, so editing one doesn't pointlessly flag
     * `locallyModified` (which would force the next sync cycle to scan
     * a never-syncable playlist).
     *
     * Fix 2 of the multi-provider mirror-propagation rules from desktop
     * CLAUDE.md.
     */
    private suspend fun hasSyncIntent(playlistId: String): Boolean {
        val source = syncPlaylistSourceDao.selectForLocal(playlistId)
        if (source != null) return true
        return syncPlaylistLinkDao.selectForLocal(playlistId).isNotEmpty()
    }
    fun getAllTracks(): Flow<List<TrackEntity>> = trackDao.getAll()
    fun getAllAlbums(): Flow<List<AlbumEntity>> = albumDao.getAll()
    fun getAllArtists(): Flow<List<ArtistEntity>> = artistDao.getAll()
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAll()

    /**
     * Fill in [createdAt] / [updatedAt] / [lastModified] when the caller left
     * them at the default `0L` (Playlists sort by these, so rows with 0 sink
     * to the bottom of "Recently Added" forever). Callers can still pass
     * explicit values to override; only the zero case is patched.
     */
    private fun PlaylistEntity.withInsertTimestamps(): PlaylistEntity {
        val now = System.currentTimeMillis()
        return copy(
            createdAt = if (createdAt == 0L) now else createdAt,
            updatedAt = if (updatedAt == 0L) now else updatedAt,
            lastModified = if (lastModified == 0L) now else lastModified,
        )
    }

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.search(query)
    fun searchAlbums(query: String): Flow<List<AlbumEntity>> = albumDao.search(query)

    fun getAlbumTracks(albumId: String): Flow<List<TrackEntity>> = trackDao.getByAlbumId(albumId)

    suspend fun getAlbumByTitleAndArtist(title: String, artist: String): AlbumEntity? =
        albumDao.getByTitleAndArtist(title, artist)

    suspend fun addTrack(track: TrackEntity) {
        val existing = trackDao.getById(track.id)
        // Preserve the synced addedAt timestamp if one exists
        trackDao.insert(if (existing != null) track.copy(addedAt = existing.addedAt) else track)
        // Background MBID enrichment
        mbidEnrichment.enrichInBackground(track.id, track.artist, track.title)
    }
    suspend fun addTracks(tracks: List<TrackEntity>) {
        trackDao.insertAll(tracks)
        // Background MBID enrichment for batch imports
        mbidEnrichment.enrichBatchInBackground(
            tracks.map { TrackEnrichmentRequest(it.id, it.artist, it.title) }
        )
    }
    suspend fun addAlbum(album: AlbumEntity) = albumDao.insert(album)
    suspend fun addAlbums(albums: List<AlbumEntity>) = albumDao.insertAll(albums)
    suspend fun addArtist(artist: ArtistEntity) = artistDao.insert(artist)
    suspend fun addArtists(artists: List<ArtistEntity>) = artistDao.insertAll(artists)
    suspend fun addPlaylist(playlist: PlaylistEntity) =
        playlistDao.insert(playlist.withInsertTimestamps())

    /** Backfill lastModified from updatedAt for playlists synced before tracking. */
    suspend fun backfillPlaylistLastModified() = playlistDao.backfillLastModified()

    /** Reactive check whether a track exists in collection by title+artist. */
    fun isTrackInCollection(title: String, artist: String): Flow<Boolean> =
        trackDao.existsByTitleAndArtist(title, artist)

    /** Reactive check whether an artist exists in collection by name. */
    fun isArtistInCollection(name: String): Flow<Boolean> =
        artistDao.existsByName(name)

    suspend fun deleteArtistByName(name: String) = artistDao.deleteByName(name)

    /** Reactive check whether an album exists in collection by title+artist. */
    fun isAlbumInCollection(title: String, artist: String): Flow<Boolean> =
        albumDao.existsByTitleAndArtist(title, artist)

    suspend fun deleteTrack(track: TrackEntity) = trackDao.delete(track)
    suspend fun deleteAlbum(album: AlbumEntity) = albumDao.delete(album)
    suspend fun deletePlaylist(playlist: PlaylistEntity) = playlistDao.delete(playlist)

    /**
     * Phase 6.5 — delete a playlist locally AND attempt to delete each
     * remote mirror. Returns the per-provider attempt results so the
     * caller (a ViewModel) can surface a toast if any provider returned
     * `Unsupported` (per Decision D8 — Apple Music returns 401 on
     * `DELETE /me/library/playlists/{id}`, so the AM mirror persists
     * and the user has to remove it manually in the Music app).
     *
     * Order matters: remote-cleanup runs FIRST so the per-provider link
     * rows are consulted before the local row's foreign-key cascade
     * blows them away. The local delete is still last so a partial
     * failure doesn't leave the local row referencing dead remotes.
     */
    suspend fun deletePlaylistWithSync(
        playlist: PlaylistEntity,
    ): List<SyncEngine.PlaylistDeletionAttempt> {
        val attempts = syncEngine.onPlaylistRemoved(playlist)
        playlistDao.delete(playlist)
        return attempts
    }

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
        playlistDao.insert(playlist.withInsertTimestamps())
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
        // Fix 2: only flag locallyModified when the playlist has sync
        // intent (syncedFrom OR any syncedTo). Editing a local-only
        // playlist shouldn't pointlessly flag a never-syncable row.
        // Preserve an existing locallyModified=true (don't downgrade
        // a flag set by an earlier mutator that the push loop hasn't
        // cleared yet).
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            trackCount = playlist.trackCount + tracks.size,
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /** Rename a playlist. */
    suspend fun renamePlaylist(playlistId: String, newName: String) {
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = System.currentTimeMillis()
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            name = newName,
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /** Reorder playlist tracks by replacing all positions. */
    suspend fun reorderPlaylistTracks(playlistId: String, tracks: List<PlaylistTrackEntity>) {
        val reindexed = tracks.mapIndexed { index, track ->
            track.copy(position = index)
        }
        playlistTrackDao.replaceAll(playlistId, reindexed)
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = System.currentTimeMillis()
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /** Remove a single track from a playlist by position and update the count. */
    suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) {
        playlistTrackDao.deleteTrack(playlistId, position)
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = System.currentTimeMillis()
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            trackCount = (playlist.trackCount - 1).coerceAtLeast(0),
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /**
     * Backfill resolver IDs on a stored track from resolution results.
     * Only fills in IDs that are currently null/blank — never overwrites.
     */
    suspend fun backfillTrackResolverIds(
        trackId: String,
        spotifyId: String?,
        spotifyUri: String?,
        appleMusicId: String?,
        soundcloudId: String?,
    ) {
        trackDao.backfillResolverIds(trackId, spotifyId, spotifyUri, appleMusicId, soundcloudId)
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
