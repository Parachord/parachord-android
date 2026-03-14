package com.parachord.android.ui.screens.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friendsRepository: FriendsRepository,
) : ViewModel() {

    val friends: StateFlow<List<FriendEntity>> = friendsRepository.getAllFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showAddDialog = MutableStateFlow(false)
    val addFriendInput = MutableStateFlow("")
    val addFriendState = MutableStateFlow<Resource<Unit>?>(null)

    fun openAddDialog() {
        addFriendInput.value = ""
        addFriendState.value = null
        showAddDialog.value = true
    }

    fun dismissAddDialog() {
        showAddDialog.value = false
        addFriendInput.value = ""
        addFriendState.value = null
    }

    fun setAddFriendInput(input: String) {
        addFriendInput.value = input
    }

    fun addFriend() {
        val input = addFriendInput.value.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            addFriendState.value = Resource.Loading
            val result = friendsRepository.addFriend(input)
            when (result) {
                is Resource.Success -> {
                    addFriendState.value = Resource.Success(Unit)
                    // Auto-dismiss after success
                    showAddDialog.value = false
                    addFriendInput.value = ""
                    addFriendState.value = null
                }
                is Resource.Error -> {
                    addFriendState.value = Resource.Error(result.message)
                }
                is Resource.Loading -> { /* shouldn't happen */ }
            }
        }
    }

    fun removeFriend(friendId: String) {
        viewModelScope.launch {
            friendsRepository.removeFriend(friendId)
        }
    }

    fun togglePin(friend: FriendEntity) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friend.id, !friend.pinnedToSidebar)
        }
    }

    fun pinFriend(friendId: String) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friendId, true)
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            friends.value.forEach { friend ->
                friendsRepository.refreshFriendActivity(friend)
            }
        }
    }

    init {
        // Sync friends from Last.fm/ListenBrainz, then refresh activity
        viewModelScope.launch {
            friendsRepository.syncFriendsFromServices()
            refreshAll()
        }
    }
}
