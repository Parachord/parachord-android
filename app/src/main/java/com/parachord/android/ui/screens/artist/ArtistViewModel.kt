package com.parachord.android.ui.screens.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.metadata.ArtistInfo
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadataService: MetadataService,
) : ViewModel() {

    private val artistName: String = savedStateHandle.get<String>("artistName") ?: ""

    private val _artistInfo = MutableStateFlow<ArtistInfo?>(null)
    val artistInfo: StateFlow<ArtistInfo?> = _artistInfo.asStateFlow()

    private val _topTracks = MutableStateFlow<List<TrackSearchResult>>(emptyList())
    val topTracks: StateFlow<List<TrackSearchResult>> = _topTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        if (artistName.isNotBlank()) {
            loadArtist()
        }
    }

    private fun loadArtist() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Cascading artist info lookup
                _artistInfo.value = metadataService.getArtistInfo(artistName)
                // Also search for their tracks
                _topTracks.value = metadataService.searchTracks(artistName, limit = 10)
            } catch (_: Exception) {
                // partial results still shown
            } finally {
                _isLoading.value = false
            }
        }
    }
}
