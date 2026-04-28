package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Search_history
import com.parachord.shared.model.SearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [SearchHistoryQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class SearchHistoryDao(private val db: ParachordDb) {

    private val queries get() = db.searchHistoryQueries

    /* ---- Mapping ---- */

    private fun Search_history.toSearchHistory() = SearchHistory(
        id = id,
        query = query,
        resultType = resultType,
        resultName = resultName,
        resultArtist = resultArtist,
        artworkUrl = artworkUrl,
        timestamp = timestamp,
    )

    /* ---- Queries returning Flow ---- */

    fun getRecent(): Flow<List<SearchHistory>> =
        queries.getRecent().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toSearchHistory() } }

    /* ---- Writes ---- */

    suspend fun insert(entry: SearchHistory): Unit = withContext(Dispatchers.Default) {
        queries.insert(
            query = entry.query,
            resultType = entry.resultType,
            resultName = entry.resultName,
            resultArtist = entry.resultArtist,
            artworkUrl = entry.artworkUrl,
            timestamp = entry.timestamp,
        )
    }

    /** Delete older entries with the same query (case-insensitive) before inserting. */
    suspend fun deleteByQuery(query: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteByQuery(query)
    }

    suspend fun deleteById(id: Long): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(id)
    }

    suspend fun clearAll(): Unit = withContext(Dispatchers.Default) {
        queries.clearAll()
    }

    /** Keep only the 50 most recent entries. */
    suspend fun trimToLimit(): Unit = withContext(Dispatchers.Default) {
        queries.trimToLimit()
    }
}
