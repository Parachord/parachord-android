package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.PlaylistTrackInfo
import com.parachord.android.resolver.TrackResolverCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistDao: PlaylistDao,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val playbackStateHolder: com.parachord.android.playback.PlaybackStateHolder,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
) : ViewModel() {

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val playlistId: String = savedStateHandle["playlistId"] ?: ""

    val playlist: StateFlow<PlaylistEntity?> = playlistDao.getByIdFlow(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Title of the currently playing track (for highlight). */
    val nowPlayingTitle: StateFlow<String?> = playbackStateHolder.state
        .map { it.currentTrack?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracks: StateFlow<List<PlaylistTrackEntity>> =
        libraryRepository.getPlaylistTracks(playlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Resolver badge names for UI display — shared across all screens via TrackResolverCache. */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    init {
        // Run full resolver pipeline against playlist tracks in background (concurrent, deduplicated)
        viewModelScope.launch {
            tracks.collect { trackList ->
                if (trackList.isNotEmpty()) {
                    trackResolverCache.resolvePlaylistTracksInBackground(
                        trackList.map { pt ->
                            PlaylistTrackInfo(
                                title = pt.trackTitle,
                                artist = pt.trackArtist,
                                spotifyId = pt.trackSpotifyId,
                                appleMusicId = pt.trackAppleMusicId,
                                soundcloudId = pt.trackSoundcloudId,
                            )
                        }
                    )
                }
            }
        }
    }

    /** Play all tracks starting from the given index. */
    fun playAll(startIndex: Int = 0) {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        playbackController.playQueue(entities, startIndex)
    }

    /** Play a single track from the playlist. */
    fun playTrack(index: Int) {
        playAll(startIndex = index)
    }

    /** Get all playlists for the playlist picker. */
    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Context menu actions ─────────────────────────────────────────

    fun trackEntityAt(index: Int): TrackEntity? {
        val pt = tracks.value.getOrNull(index) ?: return null
        return libraryRepository.playlistTrackToTrackEntity(pt)
    }

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(targetPlaylist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(targetPlaylist.id, listOf(track))
        }
    }

    fun removeFromPlaylist(position: Int) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, position)
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTrack(track)
        }
    }

    /** Add all playlist tracks to the queue without interrupting playback. */
    fun queueAll() {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        playbackController.addToQueue(entities)
    }

    /** Delete this playlist. */
    fun deletePlaylist() {
        viewModelScope.launch {
            playlist.value?.let { libraryRepository.deletePlaylist(it) }
        }
    }
}
