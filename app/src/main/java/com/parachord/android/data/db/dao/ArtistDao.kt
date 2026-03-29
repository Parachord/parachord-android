package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.parachord.android.data.db.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE spotifyId = :spotifyId")
    suspend fun getBySpotifyId(spotifyId: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    fun getByName(name: String): Flow<ArtistEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM artists WHERE name = :name)")
    fun existsByName(name: String): Flow<Boolean>

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artists: List<ArtistEntity>)

    @Delete
    suspend fun delete(artist: ArtistEntity)

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM artists WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("UPDATE artists SET imageUrl = :imageUrl WHERE name = :name AND (imageUrl IS NULL OR imageUrl = '')")
    suspend fun updateImageByName(name: String, imageUrl: String)
}
