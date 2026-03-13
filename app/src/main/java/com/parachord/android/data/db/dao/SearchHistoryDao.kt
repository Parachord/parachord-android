package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.parachord.android.data.db.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 50")
    fun getRecent(): Flow<List<SearchHistoryEntity>>

    @Insert
    suspend fun insert(entry: SearchHistoryEntity)

    /** Delete older entries with the same query (case-insensitive) before inserting. */
    @Query("DELETE FROM search_history WHERE LOWER(query) = LOWER(:query)")
    suspend fun deleteByQuery(query: String)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    /** Keep only the 50 most recent entries. */
    @Query("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY timestamp DESC LIMIT 50)")
    suspend fun trimToLimit()
}
