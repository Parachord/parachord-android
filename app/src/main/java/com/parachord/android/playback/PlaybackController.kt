package com.parachord.android.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.playback.handlers.PlaybackAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the UI layer to the PlaybackService via MediaController, and routes
 * playback through the PlaybackRouter for multi-source support.
 *
 * Queue management is delegated to [QueueManager] which mirrors the desktop
 * app's queue logic (current track separate from queue, play history, etc.).
 *
 * Handles two playback modes:
 * - ExoPlayer: local files, direct streams, SoundCloud (all via MediaController)
 * - External: Spotify Connect via Web API (manages its own playback lifecycle)
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: PlaybackStateHolder,
    private val router: PlaybackRouter,
    private val queueManager: QueueManager,
    private val queuePersistence: QueuePersistence,
    private val scrobbleManager: ScrobbleManager,
    private val imageEnrichment: ImageEnrichmentService,
) {
    companion object {
        private const val TAG = "PlaybackController"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var spotifyStateJob: Job? = null

    /** Whether playback is currently managed externally (e.g. Spotify Connect). */
    private var isExternalPlayback = false

    /** Listener called when a track naturally completes (auto-advance, not user skip). */
    var onTrackEndedListener: (() -> Unit)? = null

    /** Listener called when the user manually changes playback (skip, play different track). */
    var onUserPlaybackActionListener: (() -> Unit)? = null

    fun connect() {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener({
            controller = future.get()
            setupPlayerListener()
            // Restore persisted queue (if enabled) and start auto-save
            scope.launch {
                val restoredTrack = queuePersistence.restoreIfEnabled()
                if (restoredTrack != null) {
                    val snapshot = queueManager.snapshot.value
                    stateHolder.update {
                        copy(
                            currentTrack = restoredTrack,
                            isPlaying = false,
                            position = 0L,
                            upNext = snapshot.upNext,
                            playbackContext = snapshot.playbackContext,
                            shuffleEnabled = snapshot.shuffleEnabled,
                        )
                    }
                }
                queuePersistence.startObserving()
                scrobbleManager.startObserving()
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        positionUpdateJob?.cancel()
        spotifyStateJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        scope.launch { router.stopExternalPlayback() }
    }

    /** Play a single track immediately (clears the queue). */
    fun playTrack(track: TrackEntity, context: PlaybackContext? = null) {
        onUserPlaybackActionListener?.invoke()
        queueManager.clearQueue()
        if (context != null) queueManager.setContext(context)
        scope.launch { playTrackInternal(track) }
    }

    /**
     * Play a list of tracks starting at the given index.
     * Remaining tracks become the queue, tagged with [context].
     */
    fun playQueue(
        tracks: List<TrackEntity>,
        startIndex: Int = 0,
        context: PlaybackContext? = null,
        shuffle: Boolean = queueManager.shuffleEnabled,
    ) {
        onUserPlaybackActionListener?.invoke()
        val track = queueManager.setQueue(tracks, startIndex, context, shuffle) ?: return
        scope.launch { playTrackInternal(track) }
    }

    /** Append tracks to the end of the queue. */
    fun addToQueue(tracks: List<TrackEntity>) {
        queueManager.addToQueue(tracks)
        syncQueueState()
    }

    /** Insert tracks at the front of the queue (play next). */
    fun insertNext(tracks: List<TrackEntity>) {
        queueManager.insertNext(tracks)
        syncQueueState()
    }

    fun skipNext() {
        skipNextInternal(userInitiated = true)
    }

    private fun skipNextInternal(userInitiated: Boolean) {
        if (userInitiated) {
            onUserPlaybackActionListener?.invoke()
        } else {
            onTrackEndedListener?.invoke()
        }
        val currentTrack = stateHolder.state.value.currentTrack
        val next = queueManager.skipNext(currentTrack)
        if (next == null) {
            Log.d(TAG, "skipNext: queue empty, stopping playback")
            if (isExternalPlayback) {
                scope.launch { router.stopExternalPlayback() }
                isExternalPlayback = false
            }
            stateHolder.update { copy(isPlaying = false) }
            return
        }
        Log.d(TAG, "skipNext: advancing to '${next.title}' by ${next.artist}")
        scope.launch {
            if (isExternalPlayback) router.stopExternalPlayback()
            playTrackInternal(next)
        }
    }

    fun skipPrevious() {
        onUserPlaybackActionListener?.invoke()
        if (isExternalPlayback) {
            val position = router.getSpotifyHandler().getPosition()
            if (position > 3000) {
                scope.launch { router.activeExternalHandler?.seekTo(0) }
                return
            }
        } else {
            val ctrl = controller ?: return
            if (ctrl.currentPosition > 3000) {
                ctrl.seekTo(0)
                return
            }
        }

        val currentTrack = stateHolder.state.value.currentTrack
        val prev = queueManager.skipPrevious(currentTrack) ?: return
        scope.launch {
            if (isExternalPlayback) router.stopExternalPlayback()
            playTrackInternal(prev)
        }
    }

    /** User tapped a track in the queue UI — play from that point. */
    fun playFromQueue(index: Int) {
        val currentTrack = stateHolder.state.value.currentTrack
        val track = queueManager.playFromQueue(index, currentTrack) ?: return
        scope.launch {
            if (isExternalPlayback) router.stopExternalPlayback()
            playTrackInternal(track)
        }
    }

    /** Drag-to-reorder in the queue. */
    fun moveInQueue(from: Int, to: Int) {
        queueManager.moveInQueue(from, to)
        syncQueueState()
    }

    /** Remove a single track from the queue. */
    fun removeFromQueue(index: Int) {
        queueManager.removeFromQueue(index)
        syncQueueState()
    }

    /** Clear the queue. */
    fun clearQueue() {
        queueManager.clearQueue()
        syncQueueState()
    }

    fun togglePlayPause() {
        if (isExternalPlayback) {
            scope.launch {
                val handler = router.activeExternalHandler ?: return@launch
                val spotify = router.getSpotifyHandler()
                if (spotify.isPlaying()) {
                    handler.pause()
                    stateHolder.update { copy(isPlaying = false) }
                } else {
                    handler.resume()
                    stateHolder.update { copy(isPlaying = true) }
                }
            }
            return
        }

        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun toggleShuffle() {
        val newState = queueManager.toggleShuffle()
        stateHolder.update { copy(shuffleEnabled = newState) }
        if (!isExternalPlayback) {
            controller?.shuffleModeEnabled = false // We manage shuffle ourselves
        }
        syncQueueState()
    }

    fun seekTo(positionMs: Long) {
        if (isExternalPlayback) {
            scope.launch {
                router.activeExternalHandler?.seekTo(positionMs)
                stateHolder.update { copy(position = positionMs) }
            }
            return
        }
        controller?.seekTo(positionMs)
    }

    private suspend fun playTrackInternal(track: TrackEntity) {
        val action = router.route(track)
        val snapshot = queueManager.snapshot.value

        if (action == null) {
            Log.w(TAG, "No playback handler for: ${track.title}")
            playViaExoPlayer(track)
            return
        }

        when (action) {
            is PlaybackAction.ExoPlayerItem -> {
                isExternalPlayback = false
                stopSpotifyStatePolling()

                val ctrl = controller ?: return
                ctrl.stop()
                ctrl.setMediaItems(listOf(action.mediaItem), 0, 0L)
                ctrl.prepare()
                ctrl.play()

                stateHolder.update {
                    copy(
                        currentTrack = track,
                        isPlaying = true,
                        position = 0L,
                        upNext = snapshot.upNext,
                        playbackContext = snapshot.playbackContext,
                        shuffleEnabled = snapshot.shuffleEnabled,
                    )
                }
            }

            is PlaybackAction.ExternalPlayback -> {
                isExternalPlayback = true
                stopPositionUpdates()
                controller?.stop()

                // Set UI state optimistically — show the track as playing immediately
                // so the user gets instant feedback. The state polling will correct
                // this if playback actually fails.
                stateHolder.update {
                    copy(
                        currentTrack = track,
                        isPlaying = true,
                        position = 0L,
                        duration = track.duration ?: 0L,
                        upNext = snapshot.upNext,
                        playbackContext = snapshot.playbackContext,
                        shuffleEnabled = snapshot.shuffleEnabled,
                    )
                }

                action.handler.play(track)
                startSpotifyStatePolling()
            }
        }

        // If the track has no artwork, try to fetch it in the background
        enrichArtworkIfMissing(track)
    }

    /** Fallback: play directly via ExoPlayer when no handler matches. */
    private fun playViaExoPlayer(track: TrackEntity) {
        val ctrl = controller ?: return
        isExternalPlayback = false
        stopSpotifyStatePolling()

        val mediaItem = track.toMediaItem()
        ctrl.stop()
        ctrl.setMediaItems(listOf(mediaItem), 0, 0L)
        ctrl.prepare()
        ctrl.play()

        val snapshot = queueManager.snapshot.value
        stateHolder.update {
            copy(
                currentTrack = track,
                isPlaying = true,
                position = 0L,
                upNext = snapshot.upNext,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
            )
        }
        enrichArtworkIfMissing(track)
    }

    /** Push current queue snapshot to PlaybackState without changing playback fields. */
    private fun syncQueueState() {
        val snapshot = queueManager.snapshot.value
        stateHolder.update {
            copy(
                upNext = snapshot.upNext,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
            )
        }
    }

    private fun setupPlayerListener() {
        val ctrl = controller ?: return
        ctrl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isExternalPlayback) {
                    syncState()
                    if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isExternalPlayback) syncState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isExternalPlayback) {
                    syncState()
                    if (playbackState == Player.STATE_ENDED) {
                        skipNextInternal(userInitiated = false)
                    }
                }
            }
        })
        syncState()
        if (ctrl.isPlaying) startPositionUpdates()
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val snapshot = queueManager.snapshot.value

        stateHolder.update {
            copy(
                isPlaying = ctrl.isPlaying,
                position = ctrl.currentPosition.coerceAtLeast(0L),
                duration = ctrl.duration.coerceAtLeast(0L),
                upNext = snapshot.upNext,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
            )
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                val ctrl = controller
                if (ctrl != null && ctrl.isPlaying && !isExternalPlayback) {
                    stateHolder.update {
                        copy(
                            position = ctrl.currentPosition.coerceAtLeast(0L),
                            duration = ctrl.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    /** Poll Spotify Web API for position/state when playing externally. */
    private fun startSpotifyStatePolling() {
        spotifyStateJob?.cancel()
        spotifyStateJob = scope.launch {
            val spotify = router.getSpotifyHandler()
            // Small initial delay to let the track start
            delay(1000)
            while (isActive && isExternalPlayback) {
                val playing = spotify.isPlaying()
                val position = spotify.getPosition()
                val duration = spotify.getDuration()

                stateHolder.update {
                    copy(
                        isPlaying = playing,
                        position = position,
                        duration = duration,
                    )
                }

                // Auto-advance: detect when our track is done via multiple signals
                // (natural end, Spotify autoplay to different track, or item cleared)
                if (spotify.isOurTrackDone()) {
                    skipNextInternal(userInitiated = false)
                    break
                }

                delay(500)
            }
        }
    }

    private fun stopSpotifyStatePolling() {
        spotifyStateJob?.cancel()
    }

    /**
     * If the currently playing track has no artwork, try to fetch it from
     * metadata providers in the background. Updates both the DB and the
     * live PlaybackState so the UI refreshes without a restart.
     */
    private fun enrichArtworkIfMissing(track: TrackEntity) {
        if (!track.artworkUrl.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            val url = imageEnrichment.enrichTrackArt(
                trackId = track.id,
                trackTitle = track.title,
                artistName = track.artist,
                albumTitle = track.album,
            ) ?: return@launch
            // Update live state if this track is still playing
            stateHolder.update {
                if (currentTrack?.id == track.id) {
                    copy(currentTrack = currentTrack?.copy(artworkUrl = url))
                } else this
            }
        }
    }
}

private fun TrackEntity.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .apply {
            artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) }
        }
        .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(sourceUrl ?: "")
        .setMediaMetadata(metadata)
        .build()
}
