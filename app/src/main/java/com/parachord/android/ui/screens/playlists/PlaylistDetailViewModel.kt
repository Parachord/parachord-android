package com.parachord.android.ui.screens.playlists

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.PlaylistTrackInfo
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.sync.SpotifySyncProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class PlaylistDetailViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val playbackStateHolder: com.parachord.android.playback.PlaybackStateHolder,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
    private val spotifySyncProvider: SpotifySyncProvider,
    private val imageEnrichmentService: ImageEnrichmentService,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
) : ViewModel() {

    companion object {
        private const val TAG = "PlaylistDetailVM"
    }

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

    /** True when the remote playlist (Spotify) has changes not yet pulled locally. */
    private val _hasRemoteUpdate = MutableStateFlow(false)
    val hasRemoteUpdate: StateFlow<Boolean> = _hasRemoteUpdate.asStateFlow()

    /** True while pulling remote changes. */
    private val _isPulling = MutableStateFlow(false)
    val isPulling: StateFlow<Boolean> = _isPulling.asStateFlow()

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
                                // Pass row coords so TrackResolverCache can
                                // backfill resolver IDs onto the DB row —
                                // subsequent opens of a Spotify-synced
                                // playlist show all resolver badges from
                                // the first render, not just the Spotify one.
                                playlistId = pt.playlistId,
                                position = pt.position,
                            )
                        }
                    )
                }
            }
        }

        // Generate the mosaic / enrich track art when the playlist has no
        // cover. Required for hosted XSPF imports — XSPF doesn't carry per-
        // track image data, so without this the detail screen would always
        // show the gray "QueueMusic" placeholder. Fire-and-forget; the
        // service deduplicates concurrent calls and the underlying mosaic
        // file is written once.
        viewModelScope.launch {
            playlist.collect { p ->
                if (p != null && p.artworkUrl.isNullOrBlank()) {
                    imageEnrichmentService.enrichPlaylistArt(p.id)
                }
            }
        }

        // Check if the remote playlist has been updated since last sync
        checkForRemoteUpdate()
    }

    fun checkForRemoteUpdate() {
        viewModelScope.launch {
            val pl = playlistDao.getById(playlistId) ?: return@launch
            // Hosted XSPF playlists are canonical via their `sourceUrl` — the
            // 5-minute poller is the single source of truth, and SyncEngine
            // only ever pushes them upstream, never pulls. A "pull from
            // Spotify" banner here would offer the user a destructive no-op
            // (the next poll tick would overwrite it), so the flag is
            // suppressed entirely. Mirrors desktop's pull-banner behavior
            // for hosted rows (CLAUDE.md "Hosted XSPF Playlists").
            if (pl.sourceUrl != null) return@launch
            val spotifyId = pl.spotifyId ?: return@launch
            val localSnapshot = pl.snapshotId ?: return@launch

            try {
                val remoteSnapshot = spotifySyncProvider.getPlaylistSnapshotId(spotifyId)
                if (remoteSnapshot != null && remoteSnapshot != localSnapshot) {
                    Log.d(TAG, "Remote update detected: local=$localSnapshot, remote=$remoteSnapshot")
                    _hasRemoteUpdate.value = true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to check remote playlist update: ${e.message}")
            }
        }
    }

    /** Pull the latest tracks from the remote source (Spotify). */
    fun pullRemoteChanges() {
        viewModelScope.launch {
            val pl = playlistDao.getById(playlistId) ?: return@launch
            val spotifyId = pl.spotifyId ?: return@launch

            _isPulling.value = true
            try {
                val remoteTracks = spotifySyncProvider.fetchPlaylistTracks(spotifyId)
                val remoteSnapshot = spotifySyncProvider.getPlaylistSnapshotId(spotifyId)

                playlistTrackDao.deleteByPlaylistId(playlistId)
                playlistTrackDao.insertAll(remoteTracks)

                val now = System.currentTimeMillis()
                // Fix 1 (multi-provider mirror propagation): if this playlist
                // has push-mirror entries on providers OTHER than Spotify
                // (e.g. Apple Music), those copies are now stale relative
                // to the just-pulled state. Flag locallyModified so the
                // next push loop catches them up. Without this, an
                // Android-edit → Spotify → desktop pull stops at the
                // desktop and never reaches Apple Music.
                val hasOtherMirrors = syncPlaylistLinkDao.hasOtherMirrors(playlistId, "spotify")
                playlistDao.update(pl.copy(
                    trackCount = remoteTracks.size,
                    snapshotId = remoteSnapshot ?: pl.snapshotId,
                    updatedAt = now,
                    lastModified = now,
                    locallyModified = hasOtherMirrors,
                ))

                _hasRemoteUpdate.value = false
                Log.d(TAG, "Pulled ${remoteTracks.size} tracks from Spotify playlist $spotifyId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull remote changes: ${e.message}", e)
            } finally {
                _isPulling.value = false
            }
        }
    }

    /** Dismiss the remote update banner without pulling. */
    fun dismissRemoteUpdate() {
        _hasRemoteUpdate.value = false
    }

    /** Play all tracks starting from the given index. */
    fun playAll(startIndex: Int = 0) {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        val context = PlaybackContext(
            type = "playlist",
            name = playlist.value?.name ?: "Playlist",
            id = playlistId,
        )
        playbackController.playQueue(entities, startIndex, context = context)
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
