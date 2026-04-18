package com.parachord.android.data.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Playlist_tracks
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [PlaylistTrackQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class PlaylistTrackDao(private val db: ParachordDb) {

    private val queries get() = db.playlistTrackQueries

    /* ---- Mapping ---- */

    private fun Playlist_tracks.toPlaylistTrack() = PlaylistTrack(
        playlistId = playlistId,
        position = position.toInt(),
        trackTitle = trackTitle,
        trackArtist = trackArtist,
        trackAlbum = trackAlbum,
        trackDuration = trackDuration,
        trackArtworkUrl = trackArtworkUrl,
        trackSourceUrl = trackSourceUrl,
        trackResolver = trackResolver,
        trackSpotifyUri = trackSpotifyUri,
        trackSoundcloudId = trackSoundcloudId,
        trackSpotifyId = trackSpotifyId,
        trackAppleMusicId = trackAppleMusicId,
        addedAt = addedAt,
    )

    /* ---- Queries returning Flow ---- */

    fun getByPlaylistId(playlistId: String): Flow<List<PlaylistTrackEntity>> =
        queries.getByPlaylistId(playlistId).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toPlaylistTrack() } }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getByPlaylistIdSync(playlistId: String): List<PlaylistTrackEntity> = withContext(Dispatchers.IO) {
        queries.getByPlaylistId(playlistId).executeAsList().map { it.toPlaylistTrack() }
    }

    suspend fun getMaxPosition(playlistId: String): Int = withContext(Dispatchers.IO) {
        queries.getMaxPosition(playlistId).executeAsOne().toInt()
    }

    /* ---- Writes ---- */

    suspend fun insertAll(tracks: List<PlaylistTrackEntity>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            for (track in tracks) {
                queries.insert(
                    playlistId = track.playlistId,
                    position = track.position.toLong(),
                    trackTitle = track.trackTitle,
                    trackArtist = track.trackArtist,
                    trackAlbum = track.trackAlbum,
                    trackDuration = track.trackDuration,
                    trackArtworkUrl = track.trackArtworkUrl,
                    trackSourceUrl = track.trackSourceUrl,
                    trackResolver = track.trackResolver,
                    trackSpotifyUri = track.trackSpotifyUri,
                    trackSoundcloudId = track.trackSoundcloudId,
                    trackSpotifyId = track.trackSpotifyId,
                    trackAppleMusicId = track.trackAppleMusicId,
                    addedAt = track.addedAt,
                )
            }
        }
    }

    suspend fun deleteByPlaylistId(playlistId: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteByPlaylistId(playlistId)
    }

    suspend fun deleteTrack(playlistId: String, position: Int): Unit = withContext(Dispatchers.IO) {
        queries.deleteTrack(playlistId = playlistId, position = position.toLong())
    }

    /**
     * Backfill an artwork URL onto a single playlist_track row, but only when
     * it currently has none — never overwrite art that came from the source
     * (Spotify / SoundCloud already give us per-track artwork).
     */
    suspend fun updateTrackArtwork(playlistId: String, position: Int, artworkUrl: String): Unit =
        withContext(Dispatchers.IO) {
            queries.updateTrackArtwork(
                trackArtworkUrl = artworkUrl,
                playlistId = playlistId,
                position = position.toLong(),
            )
        }

    /** Delete all tracks for a playlist then reinsert — used for reorder. */
    suspend fun replaceAll(playlistId: String, tracks: List<PlaylistTrackEntity>): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteByPlaylistId(playlistId)
            for (track in tracks) {
                queries.insert(
                    playlistId = track.playlistId,
                    position = track.position.toLong(),
                    trackTitle = track.trackTitle,
                    trackArtist = track.trackArtist,
                    trackAlbum = track.trackAlbum,
                    trackDuration = track.trackDuration,
                    trackArtworkUrl = track.trackArtworkUrl,
                    trackSourceUrl = track.trackSourceUrl,
                    trackResolver = track.trackResolver,
                    trackSpotifyUri = track.trackSpotifyUri,
                    trackSoundcloudId = track.trackSoundcloudId,
                    trackSpotifyId = track.trackSpotifyId,
                    trackAppleMusicId = track.trackAppleMusicId,
                    addedAt = track.addedAt,
                )
            }
        }
    }
}
