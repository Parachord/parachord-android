package com.parachord.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.ai.AiRecommendationService
import com.parachord.android.ai.AiRecommendations
import com.parachord.android.data.repository.ChartsRepository
import com.parachord.android.data.repository.CriticalDarlingsRepository
import com.parachord.android.data.repository.FreshDropsRepository
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.android.data.repository.Resource
import com.parachord.android.data.scanner.MediaScanner
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
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
    private val playbackStateHolder: PlaybackStateHolder,
    private val mediaScanner: MediaScanner,
    private val recommendationsRepository: RecommendationsRepository,
    private val criticalDarlingsRepository: CriticalDarlingsRepository,
    private val freshDropsRepository: FreshDropsRepository,
    private val chartsRepository: ChartsRepository,
    private val aiRecommendationService: AiRecommendationService,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Current playback state for Continue Listening card. */
    val playbackState = playbackStateHolder.state

    // ── Discover previews ───────────────────────────────────────────

    private val _forYouPreview = MutableStateFlow<DiscoverPreview?>(null)
    val forYouPreview: StateFlow<DiscoverPreview?> = _forYouPreview

    private val _criticalDarlingsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val criticalDarlingsPreview: StateFlow<DiscoverPreview?> = _criticalDarlingsPreview

    private val _freshDropsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val freshDropsPreview: StateFlow<DiscoverPreview?> = _freshDropsPreview

    private val _popOfTheTopsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val popOfTheTopsPreview: StateFlow<DiscoverPreview?> = _popOfTheTopsPreview

    // ── AI Shuffleupagus ──────────────────────────────────────────

    private val _hasAiPlugins = MutableStateFlow<Boolean?>(null) // null = checking
    val hasAiPlugins: StateFlow<Boolean?> = _hasAiPlugins

    private val _aiRecommendations = MutableStateFlow<AiRecommendations?>(null)
    val aiRecommendations: StateFlow<AiRecommendations?> = _aiRecommendations

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading

    init {
        loadDiscoverPreviews()
        checkAiPlugins()
    }

    private fun checkAiPlugins() {
        viewModelScope.launch {
            val hasPlugins = aiRecommendationService.hasEnabledProvider()
            _hasAiPlugins.value = hasPlugins
            if (hasPlugins) {
                loadAiRecommendations()
            }
        }
    }

    private fun loadAiRecommendations() {
        viewModelScope.launch {
            // Show cached results immediately (stale-while-revalidate)
            aiRecommendationService.cachedRecommendations?.let { cached ->
                _aiRecommendations.value = cached
            }
            _aiLoading.value = true
            try {
                val recs = aiRecommendationService.loadRecommendations()
                _aiRecommendations.value = recs
            } catch (_: Exception) { /* handled in service */ }
            _aiLoading.value = false
        }
    }

    fun refreshAiRecommendations() {
        loadAiRecommendations()
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
        // Pop of the Tops — top charting album
        viewModelScope.launch {
            try {
                val albums = chartsRepository.getAppleMusicAlbums("us")
                albums.firstOrNull()?.let { album ->
                    _popOfTheTopsPreview.value = DiscoverPreview(
                        title = album.title,
                        subtitle = album.artist,
                        artworkUrl = album.artworkUrl,
                        label = "#1 Album",
                    )
                }
            } catch (_: Exception) { /* skip preview */ }
        }
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
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

    val playlists: StateFlow<List<PlaylistEntity>> =
        repository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            repository.addTracksToPlaylist(playlist.id, listOf(track))
        }
    }

    fun togglePin(friend: FriendEntity) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friend.id, !friend.pinnedToSidebar)
        }
    }
}
