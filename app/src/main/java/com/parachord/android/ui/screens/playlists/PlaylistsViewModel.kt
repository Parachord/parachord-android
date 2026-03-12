package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.ViewModel
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    playlistDao: PlaylistDao,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = playlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
