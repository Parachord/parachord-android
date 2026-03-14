package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.parachord.android.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getByIdFlow(id: String): Flow<PlaylistEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    suspend fun getAllSync(): List<PlaylistEntity>

    @Query("UPDATE playlists SET artworkUrl = :artworkUrl WHERE id = :id AND (artworkUrl IS NULL OR artworkUrl = '')")
    suspend fun updateArtworkById(id: String, artworkUrl: String)

    /**
     * Backfill lastModified from the most recent track addedAt in each playlist.
     * Falls back to updatedAt when a playlist has no tracks.
     */
    @Query("""
        UPDATE playlists SET lastModified = COALESCE(
            (SELECT MAX(addedAt) FROM playlist_tracks WHERE playlist_tracks.playlistId = playlists.id),
            updatedAt
        )
        WHERE lastModified = 0
    """)
    suspend fun backfillLastModified()

    @Delete
    suspend fun delete(playlist: PlaylistEntity)
}
