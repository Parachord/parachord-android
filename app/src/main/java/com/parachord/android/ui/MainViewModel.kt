package com.parachord.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val playbackController: PlaybackController,
    friendsRepository: FriendsRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    val themeMode: StateFlow<String> = settingsStore.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    /** Friends list for the sidebar drawer. */
    val friends: StateFlow<List<FriendEntity>> = friendsRepository.getAllFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        playbackController.connect()
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun skipNext() {
        playbackController.skipNext()
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.release()
    }
}
