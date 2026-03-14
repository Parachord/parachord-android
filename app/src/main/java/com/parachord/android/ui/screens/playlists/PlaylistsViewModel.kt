package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch { libraryRepository.deletePlaylist(playlist) }
    }
}
