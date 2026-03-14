package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.parachord.android.data.db.entity.FriendEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendDao {

    @Query("SELECT * FROM friends ORDER BY addedAt DESC")
    fun getAllFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends ORDER BY addedAt DESC")
    suspend fun getAllFriendsSync(): List<FriendEntity>

    @Query("SELECT * FROM friends WHERE id = :id")
    suspend fun getFriendById(id: String): FriendEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(friend: FriendEntity)

    @Query("DELETE FROM friends WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM friends WHERE pinnedToSidebar = 1 ORDER BY cachedTrackTimestamp DESC")
    fun getPinnedFriends(): Flow<List<FriendEntity>>

    @Query("UPDATE friends SET pinnedToSidebar = :pinned, autoPinned = :auto WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, auto: Boolean = false)

    @Query(
        """UPDATE friends SET
            cachedTrackName = :name,
            cachedTrackArtist = :artist,
            cachedTrackAlbum = :album,
            cachedTrackTimestamp = :timestamp,
            cachedTrackArtworkUrl = :artworkUrl,
            lastFetchedAt = :fetchedAt
        WHERE id = :id""",
    )
    suspend fun updateCachedTrack(
        id: String,
        name: String?,
        artist: String?,
        album: String?,
        timestamp: Long,
        artworkUrl: String?,
        fetchedAt: Long,
    )
}
