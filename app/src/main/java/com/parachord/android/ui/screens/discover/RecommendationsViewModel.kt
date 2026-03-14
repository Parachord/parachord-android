package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.android.data.repository.RecommendedArtist
import com.parachord.android.data.repository.RecommendedTrack
import com.parachord.android.data.repository.Resource
import com.parachord.android.resolver.ResolverManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Recommendations screen.
 * Fetches recommended artists and tracks based on listening history.
 * Supports source filtering: All | Last.fm | ListenBrainz (matching desktop).
 * Resolves tracks through the resolver pipeline for content resolver badges.
 */
@HiltViewModel
class RecommendationsViewModel @Inject constructor(
    private val recommendationsRepository: RecommendationsRepository,
    private val resolverManager: ResolverManager,
) : ViewModel() {

    companion object {
        private const val TAG = "RecommendationsVM"
    }

    private val _allArtists = MutableStateFlow<Resource<List<RecommendedArtist>>>(Resource.Loading)
    private val _allTracks = MutableStateFlow<Resource<List<RecommendedTrack>>>(Resource.Loading)

    /** Current source filter: "all", "lastfm", or "listenbrainz" */
    private val _sourceFilter = MutableStateFlow("all")
    val sourceFilter: StateFlow<String> = _sourceFilter

    /** Filtered artists based on current source filter */
    val recommendedArtists: StateFlow<Resource<List<RecommendedArtist>>> = MutableStateFlow<Resource<List<RecommendedArtist>>>(Resource.Loading)

    /** Filtered tracks based on current source filter */
    val recommendedTracks: StateFlow<Resource<List<RecommendedTrack>>> = MutableStateFlow<Resource<List<RecommendedTrack>>>(Resource.Loading)

    /** Track counts per source for filter chip labels */
    private val _sourceCounts = MutableStateFlow(SourceCounts())
    val sourceCounts: StateFlow<SourceCounts> = _sourceCounts

    init {
        // Set up filtered flows
        viewModelScope.launch {
            combine(_allArtists, _sourceFilter) { artists, filter ->
                when (artists) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> artists
                    is Resource.Success -> {
                        val filtered = if (filter == "all") {
                            artists.data
                        } else {
                            artists.data.filter { it.source == filter }
                        }
                        Resource.Success(filtered)
                    }
                }
            }.collect { (recommendedArtists as MutableStateFlow).value = it }
        }
        viewModelScope.launch {
            combine(_allTracks, _sourceFilter) { tracks, filter ->
                when (tracks) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> tracks
                    is Resource.Success -> {
                        val filtered = if (filter == "all") {
                            tracks.data
                        } else {
                            tracks.data.filter { it.source == filter }
                        }
                        Resource.Success(filtered)
                    }
                }
            }.collect { (recommendedTracks as MutableStateFlow).value = it }
        }

        loadRecommendations()
    }

    fun setSourceFilter(filter: String) {
        _sourceFilter.value = filter
    }

    fun refresh() {
        loadRecommendations()
    }

    private fun loadRecommendations() {
        viewModelScope.launch {
            recommendationsRepository.getRecommendedArtists().collect {
                _allArtists.value = it
            }
        }
        viewModelScope.launch {
            recommendationsRepository.getRecommendedTracks().collect { resource ->
                _allTracks.value = resource
                // Update source counts when tracks change
                if (resource is Resource.Success) {
                    val tracks = resource.data
                    _sourceCounts.value = SourceCounts(
                        total = tracks.size,
                        lastfm = tracks.count { it.source == "lastfm" },
                        listenbrainz = tracks.count { it.source == "listenbrainz" },
                    )
                    // Progressively resolve tracks through the resolver pipeline
                    resolveTracksProgressively(tracks)
                }
            }
        }
    }

    /**
     * Resolve recommendation tracks through the content resolver pipeline.
     * This gives us actual playback source badges (e.g. Spotify, YouTube)
     * rather than metadata source labels (Last.fm, ListenBrainz).
     * Resolves progressively so badges appear as each track is resolved.
     */
    private fun resolveTracksProgressively(tracks: List<RecommendedTrack>) {
        viewModelScope.launch {
            val mutableTracks = tracks.toMutableList()
            for ((index, track) in tracks.withIndex()) {
                if (track.resolvers.isNotEmpty()) continue // already resolved
                try {
                    val query = "${track.title} ${track.artist}"
                    val sources = resolverManager.resolve(query)
                    if (sources.isNotEmpty()) {
                        val resolverNames = sources.map { it.resolver }.distinct()
                        mutableTracks[index] = track.copy(resolvers = resolverNames)
                        _allTracks.value = Resource.Success(mutableTracks.toList())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve '${track.title}' by ${track.artist}", e)
                }
            }
        }
    }
}

data class SourceCounts(
    val total: Int = 0,
    val lastfm: Int = 0,
    val listenbrainz: Int = 0,
)
