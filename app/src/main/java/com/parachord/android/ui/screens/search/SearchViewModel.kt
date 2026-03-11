package com.parachord.android.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ArtistInfo
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.data.metadata.AlbumSearchResult
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val metadataService: MetadataService,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val debouncedQuery = _query.debounce(300)

    // Local library results (instant, from Room)
    val localTrackResults: StateFlow<List<TrackEntity>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repository.searchTracks("%$q%")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val localAlbumResults: StateFlow<List<AlbumEntity>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repository.searchAlbums("%$q%")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Remote results from cascading metadata providers
    private val _remoteTrackResults = MutableStateFlow<List<TrackSearchResult>>(emptyList())
    val remoteTrackResults: StateFlow<List<TrackSearchResult>> = _remoteTrackResults.asStateFlow()

    private val _remoteAlbumResults = MutableStateFlow<List<AlbumSearchResult>>(emptyList())
    val remoteAlbumResults: StateFlow<List<AlbumSearchResult>> = _remoteAlbumResults.asStateFlow()

    private val _artistResults = MutableStateFlow<List<ArtistInfo>>(emptyList())
    val artistResults: StateFlow<List<ArtistInfo>> = _artistResults.asStateFlow()

    private val _isSearchingRemote = MutableStateFlow(false)
    val isSearchingRemote: StateFlow<Boolean> = _isSearchingRemote.asStateFlow()

    init {
        // Launch remote search whenever the debounced query changes
        viewModelScope.launch {
            debouncedQuery.collect { q ->
                if (q.isBlank()) {
                    _remoteTrackResults.value = emptyList()
                    _remoteAlbumResults.value = emptyList()
                    _artistResults.value = emptyList()
                    _isSearchingRemote.value = false
                } else {
                    searchRemote(q)
                }
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun playLocalTrack(track: TrackEntity) {
        val allTracks = localTrackResults.value
        val index = allTracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(allTracks, startIndex = index)
    }

    private suspend fun searchRemote(query: String) {
        _isSearchingRemote.value = true
        try {
            // Run all three searches in parallel within MetadataService
            val tracks = metadataService.searchTracks(query)
            val albums = metadataService.searchAlbums(query)
            val artists = metadataService.searchArtists(query)

            _remoteTrackResults.value = tracks
            _remoteAlbumResults.value = albums
            _artistResults.value = artists
        } catch (_: Exception) {
            // Silently fail — local results still available
        } finally {
            _isSearchingRemote.value = false
        }
    }
}
