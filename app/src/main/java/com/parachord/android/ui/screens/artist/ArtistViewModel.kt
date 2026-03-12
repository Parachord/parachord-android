package com.parachord.android.ui.screens.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.metadata.AlbumSearchResult
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

    private val _albums = MutableStateFlow<List<AlbumSearchResult>>(emptyList())
    val albums: StateFlow<List<AlbumSearchResult>> = _albums.asStateFlow()

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
                _artistInfo.value = metadataService.getArtistInfo(artistName)
                _topTracks.value = metadataService.searchTracks(artistName, limit = 10)
                _albums.value = metadataService.getArtistAlbums(artistName)
            } catch (e: Exception) {
                Log.e("ArtistVM", "Failed loading artist '$artistName'", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
