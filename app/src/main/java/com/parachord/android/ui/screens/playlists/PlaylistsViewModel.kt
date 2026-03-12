package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.ViewModel
import com.parachord.android.data.db.entity.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor() : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> = MutableStateFlow(emptyList())
}
