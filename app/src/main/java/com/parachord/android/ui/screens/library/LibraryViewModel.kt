package com.parachord.android.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val imageEnrichmentService: ImageEnrichmentService,
) : ViewModel() {

    // Track which items have been enriched this session to avoid re-triggering
    private val enrichedArtists = mutableSetOf<String>()
    private val enrichedAlbums = mutableSetOf<String>()

    val tracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<ArtistEntity>> = repository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumEntity>> = repository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Sort state per tab ---

    private val _artistSort = MutableStateFlow(ArtistSort.ALPHA_ASC)
    val artistSort: StateFlow<ArtistSort> = _artistSort

    private val _albumSort = MutableStateFlow(AlbumSort.RECENT)
    val albumSort: StateFlow<AlbumSort> = _albumSort

    private val _trackSort = MutableStateFlow(TrackSort.RECENT)
    val trackSort: StateFlow<TrackSort> = _trackSort

    private val _friendSort = MutableStateFlow(FriendSort.ALPHA_ASC)
    val friendSort: StateFlow<FriendSort> = _friendSort

    // --- Search query (shared across tabs) ---

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setArtistSort(sort: ArtistSort) { _artistSort.value = sort }
    fun setAlbumSort(sort: AlbumSort) { _albumSort.value = sort }
    fun setTrackSort(sort: TrackSort) { _trackSort.value = sort }
    fun setFriendSort(sort: FriendSort) { _friendSort.value = sort }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    // --- Sorted + filtered combined artist names (merges tracks + ArtistEntity) ---

    val sortedArtistNames: StateFlow<List<String>> = combine(
        tracks, artists, _artistSort, _searchQuery,
    ) { trackList, artistList, sort, query ->
        val merged = (trackList.map { it.artist } + artistList.map { it.name })
            .distinct()
        val filtered = if (query.isBlank()) merged else {
            merged.filter { it.contains(query, ignoreCase = true) }
        }
        // For RECENT sort, we need addedAt. Build a map of name -> earliest addedAt from both sources.
        when (sort) {
            ArtistSort.ALPHA_ASC -> filtered.sortedBy { it.lowercase() }
            ArtistSort.ALPHA_DESC -> filtered.sortedByDescending { it.lowercase() }
            ArtistSort.RECENT -> {
                val artistAddedAt = mutableMapOf<String, Long>()
                for (a in artistList) {
                    artistAddedAt[a.name] = maxOf(artistAddedAt[a.name] ?: 0L, a.addedAt)
                }
                for (t in trackList) {
                    artistAddedAt[t.artist] = maxOf(artistAddedAt[t.artist] ?: 0L, t.addedAt)
                }
                filtered.sortedByDescending { artistAddedAt[it] ?: 0L }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Sorted + filtered albums ---

    val sortedAlbums: StateFlow<List<AlbumEntity>> = combine(
        albums, _albumSort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true)
            }
        }
        when (sort) {
            AlbumSort.ALPHA_ASC -> filtered.sortedBy { it.title.lowercase() }
            AlbumSort.ALPHA_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            AlbumSort.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            AlbumSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Sorted + filtered tracks ---

    val sortedTracks: StateFlow<List<TrackEntity>> = combine(
        tracks, _trackSort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    (it.album?.contains(query, ignoreCase = true) == true)
            }
        }
        when (sort) {
            TrackSort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            TrackSort.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            TrackSort.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            TrackSort.ALBUM -> filtered.sortedBy { (it.album ?: "").lowercase() }
            TrackSort.DURATION -> filtered.sortedByDescending { it.duration ?: 0L }
            TrackSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lazily fetch an artist image if not already enriched this session. */
    fun enrichArtistImageIfNeeded(artistName: String) {
        if (artistName in enrichedArtists) return
        enrichedArtists.add(artistName)
        viewModelScope.launch {
            imageEnrichmentService.enrichArtistImage(artistName)
        }
    }

    /** Lazily fetch album artwork if not already enriched this session. */
    fun enrichAlbumArtIfNeeded(albumTitle: String, artistName: String) {
        val key = "$albumTitle|$artistName"
        if (key in enrichedAlbums) return
        enrichedAlbums.add(key)
        viewModelScope.launch {
            imageEnrichmentService.enrichAlbumArt(albumTitle, artistName)
        }
    }

    fun playTrack(track: TrackEntity) {
        val allTracks = tracks.value
        val index = allTracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(allTracks, startIndex = index)
    }
}
