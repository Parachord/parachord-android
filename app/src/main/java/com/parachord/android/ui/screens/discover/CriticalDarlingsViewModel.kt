package com.parachord.android.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.repository.CriticalDarlingsRepository
import com.parachord.android.data.repository.CriticsPickAlbum
import com.parachord.android.data.repository.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Critical Darlings screen.
 *
 * Matches the desktop's critics picks state management:
 * - Loading state, albums list, sort, and search
 * - Sort options: recent (default), artist
 * - Search by album title or artist name
 */
@HiltViewModel
class CriticalDarlingsViewModel @Inject constructor(
    private val repository: CriticalDarlingsRepository,
) : ViewModel() {

    private val _albums = MutableStateFlow<Resource<List<CriticsPickAlbum>>>(Resource.Loading)
    val albums: StateFlow<Resource<List<CriticsPickAlbum>>> = _albums

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortMode = MutableStateFlow("recent")
    val sortMode: StateFlow<String> = _sortMode

    init {
        loadAlbums()
    }

    fun refresh() {
        loadAlbums(forceRefresh = true)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: String) {
        _sortMode.value = mode
    }

    /**
     * Get filtered and sorted albums based on current search and sort state.
     * Called from the composable as a derived computation.
     */
    fun filterAndSort(albums: List<CriticsPickAlbum>): List<CriticsPickAlbum> {
        val query = _searchQuery.value.lowercase().trim()
        val filtered = if (query.isBlank()) {
            albums
        } else {
            albums.filter {
                it.title.lowercase().contains(query) ||
                    it.artist.lowercase().contains(query)
            }
        }

        return when (_sortMode.value) {
            "artist" -> filtered.sortedBy { it.artist.lowercase() }
            else -> filtered // "recent" — RSS feed is already in chronological order
        }
    }

    private fun loadAlbums(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            repository.getCriticsPicks(forceRefresh).collect {
                _albums.value = it
            }
        }
    }
}
