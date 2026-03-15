package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun getAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY title")
    fun getByAlbumId(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET artworkUrl = :artworkUrl WHERE id = :id AND (artworkUrl IS NULL OR artworkUrl = '')")
    suspend fun updateArtworkById(id: String, artworkUrl: String)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    /** Get the most recent N tracks (non-reactive, for background jobs like Fresh Drops). */
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT :limit")
    suspend fun getRecentSync(limit: Int): List<TrackEntity>

    /**
     * Backfill track addedAt from sync_sources.addedAt where the sync source has a
     * non-zero addedAt (i.e., the Spotify added_at timestamp).
     * This corrects tracks whose addedAt was overwritten by System.currentTimeMillis().
     */
    @Query("""
        UPDATE tracks SET addedAt = (
            SELECT s.addedAt FROM sync_sources s
            WHERE s.itemId = tracks.id AND s.itemType = 'track' AND s.addedAt > 0
        )
        WHERE EXISTS (
            SELECT 1 FROM sync_sources s
            WHERE s.itemId = tracks.id AND s.itemType = 'track' AND s.addedAt > 0
        )
    """)
    suspend fun backfillAddedAtFromSyncSources()

    /** Check if a track exists in the collection by title+artist (case-insensitive). */
    @Query("SELECT COUNT(*) > 0 FROM tracks WHERE LOWER(title) = LOWER(:title) AND LOWER(artist) = LOWER(:artist)")
    fun existsByTitleAndArtist(title: String, artist: String): Flow<Boolean>

    /** Remove synced tracks that have no corresponding sync_source entry (orphaned duplicates). */
    @Query("DELETE FROM tracks WHERE id LIKE 'spotify-%' AND id NOT IN (SELECT itemId FROM sync_sources WHERE itemType = 'track')")
    suspend fun deleteOrphanedSyncedTracks(): Int

    /** Delete all synced tracks (spotify-prefixed IDs). */
    @Query("DELETE FROM tracks WHERE id LIKE 'spotify-%'")
    suspend fun deleteSyncedTracks(): Int

    /**
     * Update resolver IDs on an existing track without touching other fields.
     * Only fills in IDs that are currently null/blank — never overwrites existing data.
     */
    @Query("""
        UPDATE tracks SET
            spotifyId = CASE WHEN (spotifyId IS NULL OR spotifyId = '') THEN :spotifyId ELSE spotifyId END,
            spotifyUri = CASE WHEN (spotifyUri IS NULL OR spotifyUri = '') THEN :spotifyUri ELSE spotifyUri END,
            appleMusicId = CASE WHEN (appleMusicId IS NULL OR appleMusicId = '') THEN :appleMusicId ELSE appleMusicId END,
            soundcloudId = CASE WHEN (soundcloudId IS NULL OR soundcloudId = '') THEN :soundcloudId ELSE soundcloudId END
        WHERE id = :trackId
    """)
    suspend fun backfillResolverIds(
        trackId: String,
        spotifyId: String?,
        spotifyUri: String?,
        appleMusicId: String?,
        soundcloudId: String?,
    )
}
