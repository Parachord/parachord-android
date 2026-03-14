package com.parachord.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.CriticalDarlingsRepository
import com.parachord.android.data.repository.FreshDropsRepository
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.android.data.repository.Resource
import com.parachord.android.data.scanner.MediaScanner
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionStats(
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val playlists: Int = 0,
    val friends: Int = 0,
)

/** Preview snippet shown on a Discover card. */
data class DiscoverPreview(
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val label: String = "",
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val friendsRepository: FriendsRepository,
    private val playbackController: PlaybackController,
    private val mediaScanner: MediaScanner,
    private val recommendationsRepository: RecommendationsRepository,
    private val criticalDarlingsRepository: CriticalDarlingsRepository,
    private val freshDropsRepository: FreshDropsRepository,
) : ViewModel() {

    val recentTracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .map { tracks -> tracks.sortedByDescending { it.addedAt }.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null = still loading, true/false = resolved. */
    val hasLibrary: StateFlow<Boolean?> = repository.getAllTracks()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentAlbums: StateFlow<List<AlbumEntity>> = repository.getAllAlbums()
        .map { albums -> albums.sortedByDescending { it.addedAt }.take(8) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentPlaylists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .map { playlists ->
            playlists.sortedByDescending {
                if (it.lastModified > 0L) it.lastModified else it.updatedAt
            }.take(6)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val friendActivity: StateFlow<List<FriendEntity>> = friendsRepository.getAllFriends()
        .map { friends ->
            friends
                .filter { it.cachedTrackName != null }
                .sortedWith(compareByDescending<FriendEntity> { it.isOnAir }
                    .thenByDescending { it.cachedTrackTimestamp })
                .take(5)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collectionStats: StateFlow<CollectionStats> = combine(
        repository.getAllTracks().map { it.size },
        repository.getAllAlbums().map { it.size },
        repository.getAllArtists().map { it.size },
        repository.getAllPlaylists().map { it.size },
        friendsRepository.getAllFriends().map { it.size },
    ) { tracks, albums, artists, playlists, friends ->
        CollectionStats(tracks, albums, artists, playlists, friends)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionStats())

    val scanProgress: StateFlow<ScanProgress> = mediaScanner.progress

    // ── Discover previews ───────────────────────────────────────────

    private val _forYouPreview = MutableStateFlow<DiscoverPreview?>(null)
    val forYouPreview: StateFlow<DiscoverPreview?> = _forYouPreview

    private val _criticalDarlingsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val criticalDarlingsPreview: StateFlow<DiscoverPreview?> = _criticalDarlingsPreview

    private val _freshDropsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val freshDropsPreview: StateFlow<DiscoverPreview?> = _freshDropsPreview

    init {
        loadDiscoverPreviews()
    }

    private fun loadDiscoverPreviews() {
        // For You — top recommended artist
        viewModelScope.launch {
            recommendationsRepository.getRecommendedArtists().collect { resource ->
                if (resource is Resource.Success) {
                    resource.data.firstOrNull()?.let { artist ->
                        _forYouPreview.value = DiscoverPreview(
                            title = artist.name,
                            subtitle = artist.reason ?: "Based on your listening",
                            artworkUrl = artist.imageUrl,
                            label = "Top pick",
                        )
                    }
                }
            }
        }
        // Critical Darlings — latest reviewed album
        viewModelScope.launch {
            criticalDarlingsRepository.getCriticsPicks().collect { resource ->
                if (resource is Resource.Success) {
                    resource.data.firstOrNull()?.let { album ->
                        _criticalDarlingsPreview.value = DiscoverPreview(
                            title = album.title,
                            subtitle = album.artist,
                            artworkUrl = album.albumArt,
                            label = "Latest review",
                        )
                    }
                }
            }
        }
        // Fresh Drops — latest new release
        viewModelScope.launch {
            freshDropsRepository.getFreshDrops().collect { resource ->
                if (resource is Resource.Success) {
                    resource.data.firstOrNull()?.let { release ->
                        _freshDropsPreview.value = DiscoverPreview(
                            title = release.title,
                            subtitle = release.artist,
                            artworkUrl = release.albumArt,
                            label = "Latest",
                        )
                    }
                }
            }
        }
    }

    fun playTrack(track: TrackEntity) {
        val tracks = recentTracks.value
        val index = tracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(tracks, startIndex = index)
    }

    fun scanLocalMusic() {
        viewModelScope.launch {
            mediaScanner.scan()
        }
    }
}
