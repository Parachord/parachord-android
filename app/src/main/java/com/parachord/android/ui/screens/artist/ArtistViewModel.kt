package com.parachord.android.ui.screens.artist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.ArtistDao
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.metadata.AlbumSearchResult
import com.parachord.android.data.metadata.ArtistInfo
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadataService: MetadataService,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val artistDao: ArtistDao,
    private val libraryRepository: LibraryRepository,
    private val trackResolverCache: TrackResolverCache,
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

    /** Whether this artist is saved in the user's collection. */
    val isSaved: StateFlow<Boolean> = artistDao.getByName(artistName)
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Cached resolved sources for tracks, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /** Resolver badge names for UI display from shared cache */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers

    init {
        if (artistName.isNotBlank()) {
            loadArtist()
        }
    }

    /** Toggle saving/removing the artist from the collection. */
    fun toggleSaved() {
        viewModelScope.launch {
            val existing = artistDao.getByName(artistName).stateIn(viewModelScope).value
            if (existing != null) {
                // Remove from collection
                artistDao.delete(existing)
            } else {
                // Save to collection
                val info = _artistInfo.value
                artistDao.insert(ArtistEntity(
                    id = "manual-${UUID.randomUUID()}",
                    name = artistName,
                    imageUrl = info?.imageUrl,
                    genres = info?.tags?.joinToString(","),
                ))
            }
        }
    }

    private fun loadArtist() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Load all three in parallel to reduce MusicBrainz rate-limiting
                // (sequential calls would hit MB 3 times in quick succession)
                val infoDeferred = async { metadataService.getArtistInfo(artistName) }
                val tracksDeferred = async { metadataService.getArtistTopTracks(artistName, limit = 10) }
                val albumsDeferred = async { metadataService.getArtistAlbums(artistName) }

                val info = infoDeferred.await()
                _artistInfo.value = info

                val tracks = tracksDeferred.await()
                _topTracks.value = tracks

                val albums = albumsDeferred.await()
                    .sortedByDescending { it.year ?: 0 }
                _albums.value = albums

                resolveTracksInBackground(tracks)

                // Progressively enrich similar artist images that are missing
                if (info != null) {
                    enrichSimilarArtistImages(info)
                }

                // If discography came back without years/release types (MusicBrainz
                // was likely rate-limited), retry after a short delay so the user
                // sees full metadata once it loads.
                if (albums.isNotEmpty() && albums.none { it.year != null }) {
                    retryDiscography()
                }
            } catch (e: Exception) {
                Log.e("ArtistVM", "Failed loading artist '$artistName'", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Retry fetching discography after a delay. Called when the initial load
     * returned albums without year/releaseType (typically because MusicBrainz
     * was rate-limited during the parallel fetch).
     */
    private fun retryDiscography() {
        viewModelScope.launch {
            delay(2_000) // Wait for MusicBrainz rate limit to reset
            try {
                val albums = metadataService.getArtistAlbums(artistName)
                    .sortedByDescending { it.year ?: 0 }
                if (albums.any { it.year != null }) {
                    _albums.value = albums
                }
            } catch (_: Exception) { /* best effort */ }
        }
    }

    /**
     * Progressively fetch images for similar artists that are missing them.
     *
     * Uses lightweight searchArtists() instead of full getArtistInfo() to avoid
     * a cascade: getArtistInfo() would call 5 providers + enrich THEIR 20 similar
     * artists with Spotify, causing 500+ API calls and potential OOM/crashes.
     * searchArtists() just queries provider indexes — no bios, similar artists, etc.
     */
    private fun enrichSimilarArtistImages(info: ArtistInfo) {
        viewModelScope.launch {
            val artists = info.similarArtists.toMutableList()
            val toEnrich = artists.withIndex().filter { it.value.imageUrl == null }
            if (toEnrich.isEmpty()) return@launch

            Log.d("ArtistVM", "Enriching images for ${toEnrich.size}/${artists.size} similar artists")
            for ((index, artist) in toEnrich) {
                try {
                    val results = metadataService.searchArtists(artist.name, limit = 1)
                    val imageUrl = results.firstOrNull()?.imageUrl
                    if (imageUrl != null) {
                        artists[index] = artist.copy(imageUrl = imageUrl)
                        _artistInfo.value = _artistInfo.value?.copy(similarArtists = artists.toList())
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    /**
     * Resolve and play a top track. Uses cached sources if available,
     * otherwise resolves on-the-fly. Follows the same pattern as
     * HistoryViewModel and FriendDetailViewModel.
     */
    fun playTrack(track: TrackSearchResult) {
        viewModelScope.launch {
            try {
                val key = trackKey(track.title, track.artist)
                val sources = _trackSources.value[key]
                    ?: resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                    )
                val best = resolverScoring.selectBest(sources)
                if (best == null) {
                    Log.w("ArtistVM", "No playable source for '${track.title}'")
                    return@launch
                }
                val entity = TrackEntity(
                    id = "artist-${track.title.hashCode()}-${track.artist.hashCode()}",
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    duration = track.duration,
                    artworkUrl = track.artworkUrl,
                    sourceType = best.sourceType,
                    sourceUrl = best.url,
                    resolver = best.resolver,
                    spotifyUri = best.spotifyUri,
                    soundcloudId = best.soundcloudId,
                    appleMusicId = best.appleMusicId,
                )
                playbackController.playTrack(entity)
            } catch (e: Exception) {
                Log.e("ArtistVM", "Failed to play '${track.title}'", e)
            }
        }
    }

    /** Get all playlists for the playlist picker. */
    val playlists: StateFlow<List<PlaylistEntity>> =
        libraryRepository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(playlist.id, listOf(track))
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTrack(track)
        }
    }

    /**
     * Build a TrackEntity from a TrackSearchResult for context menu actions.
     * Uses cached resolved sources if available.
     */
    fun trackSearchResultToEntity(track: TrackSearchResult): TrackEntity {
        val key = trackKey(track.title, track.artist)
        val sources = _trackSources.value[key]
        // Use first cached source — best effort without suspending
        val best = sources?.firstOrNull()
        return TrackEntity(
            id = "artist-${track.title.hashCode()}-${track.artist.hashCode()}",
            title = track.title,
            artist = track.artist,
            album = track.album,
            duration = track.duration,
            artworkUrl = track.artworkUrl,
            sourceType = best?.sourceType,
            sourceUrl = best?.url,
            resolver = best?.resolver,
            spotifyUri = best?.spotifyUri,
            spotifyId = best?.spotifyId,
            soundcloudId = best?.soundcloudId,
            appleMusicId = best?.appleMusicId,
        )
    }

    private fun resolveTracksInBackground(tracks: List<TrackSearchResult>) {
        viewModelScope.launch {
            for (track in tracks) {
                val key = trackKey(track.title, track.artist)
                if (_trackSources.value.containsKey(key)) continue
                // Check shared cache first (cross-context dedup)
                val cached = trackResolverCache.getSources(track.title, track.artist)
                if (cached != null) {
                    _trackSources.value = _trackSources.value + (key to cached)
                    trackResolverCache.putSources(track.title, track.artist, cached)
                    continue
                }
                try {
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.title} ${track.artist}",
                        spotifyId = track.spotifyId,
                    )
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                        trackResolverCache.putSources(track.title, track.artist, sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }
}
