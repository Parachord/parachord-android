package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistTrackDao {

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getByPlaylistId(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getByPlaylistIdSync(playlistId: String): List<PlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylistId(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND position = :position")
    suspend fun deleteTrack(playlistId: String, position: Int)

    /** Get the max position in a playlist (for appending new tracks). */
    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int

    /** Delete all tracks for a playlist then reinsert — used for reorder. */
    @androidx.room.Transaction
    suspend fun replaceAll(playlistId: String, tracks: List<PlaylistTrackEntity>) {
        deleteByPlaylistId(playlistId)
        insertAll(tracks)
    }
}
