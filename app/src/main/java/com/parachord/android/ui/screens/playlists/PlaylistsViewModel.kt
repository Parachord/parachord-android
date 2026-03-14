package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sort = MutableStateFlow(PlaylistSort.RECENT)
    val sort: StateFlow<PlaylistSort> = _sort

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSort(sort: PlaylistSort) { _sort.value = sort }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    val sortedPlaylists: StateFlow<List<PlaylistEntity>> = combine(
        playlists, _sort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }
        when (sort) {
            PlaylistSort.RECENT -> filtered.sortedByDescending { it.createdAt }
            PlaylistSort.CREATED -> filtered.sortedBy { it.createdAt }
            PlaylistSort.MODIFIED -> filtered.sortedByDescending { it.updatedAt }
            PlaylistSort.ALPHA_ASC -> filtered.sortedBy { it.name.lowercase() }
            PlaylistSort.ALPHA_DESC -> filtered.sortedByDescending { it.name.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch { libraryRepository.deletePlaylist(playlist) }
    }
}
