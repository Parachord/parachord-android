package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.parachord.android.data.db.entity.SyncSourceEntity

@Dao
interface SyncSourceDao {
    @Query("SELECT * FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType")
    suspend fun getByItem(itemId: String, itemType: String): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType")
    suspend fun getByProvider(providerId: String, itemType: String): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_sources WHERE providerId = :providerId")
    suspend fun getAllByProvider(providerId: String): List<SyncSourceEntity>

    @Query("SELECT COUNT(*) FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType")
    suspend fun countByProvider(providerId: String, itemType: String): Int

    @Query("SELECT * FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType AND providerId = :providerId")
    suspend fun get(itemId: String, itemType: String, providerId: String): SyncSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(syncSource: SyncSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(syncSources: List<SyncSourceEntity>)

    @Delete
    suspend fun delete(syncSource: SyncSourceEntity)

    @Query("DELETE FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType AND providerId = :providerId")
    suspend fun deleteByKey(itemId: String, itemType: String, providerId: String)

    @Query("DELETE FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType")
    suspend fun deleteAllForItem(itemId: String, itemType: String)

    @Query("DELETE FROM sync_sources WHERE providerId = :providerId")
    suspend fun deleteAllForProvider(providerId: String)

    @Query("SELECT * FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType ORDER BY addedAt DESC LIMIT 1")
    suspend fun getMostRecentByProvider(providerId: String, itemType: String): SyncSourceEntity?

    /** Remove orphaned sync_sources for tracks that no longer exist. */
    @Query("DELETE FROM sync_sources WHERE itemType = 'track' AND itemId NOT IN (SELECT id FROM tracks)")
    suspend fun deleteOrphanedTrackSources(): Int

    @Query("DELETE FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType")
    suspend fun deleteByProviderAndType(providerId: String, itemType: String)
}
