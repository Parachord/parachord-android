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
 * Handles three playback modes:
 * - ExoPlayer: local files, direct streams, SoundCloud (all via MediaController)
 * - External: Spotify App Remote (manages its own playback lifecycle)
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: PlaybackStateHolder,
    private val router: PlaybackRouter,
) {
    companion object {
        private const val TAG = "PlaybackController"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var spotifyStateJob: Job? = null

    /** Current queue of TrackEntity objects, kept in sync with MediaController's playlist. */
    private var currentQueue: List<TrackEntity> = emptyList()

    /** Whether playback is currently managed externally (e.g. Spotify App Remote). */
    private var isExternalPlayback = false

    fun connect() {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener({
            controller = future.get()
            setupPlayerListener()
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

    /** Play a single track immediately (clears the queue and starts fresh). */
    fun playTrack(track: TrackEntity) {
        playQueue(listOf(track), startIndex = 0)
    }

    /** Play a list of tracks, starting at the given index. */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        currentQueue = tracks
        val track = tracks.getOrNull(startIndex) ?: return

        scope.launch {
            playTrackInternal(track, startIndex)
        }
    }

    private suspend fun playTrackInternal(track: TrackEntity, queueIndex: Int) {
        val action = router.route(track)
        if (action == null) {
            Log.w(TAG, "No playback handler for: ${track.title}")
            // Fall back to ExoPlayer with whatever URL we have
            playViaExoPlayer(track, queueIndex)
            return
        }

        when (action) {
            is PlaybackAction.ExoPlayerItem -> {
                isExternalPlayback = false
                stopSpotifyStatePolling()

                val ctrl = controller ?: return
                // Stop ExoPlayer, set new item, play
                ctrl.stop()
                ctrl.setMediaItems(listOf(action.mediaItem), 0, 0L)
                ctrl.prepare()
                ctrl.play()

                // Update state immediately
                stateHolder.update {
                    copy(
                        currentTrack = track,
                        isPlaying = true,
                        position = 0L,
                        queue = currentQueue,
                        queueIndex = queueIndex,
                    )
                }
            }

            is PlaybackAction.ExternalPlayback -> {
                // Stop ExoPlayer if it was playing
                controller?.stop()
                isExternalPlayback = true

                action.handler.play(track)

                stateHolder.update {
                    copy(
                        currentTrack = track,
                        isPlaying = true,
                        position = 0L,
                        duration = track.duration ?: 0L,
                        queue = currentQueue,
                        queueIndex = queueIndex,
                    )
                }

                // Start polling Spotify state for position updates
                startSpotifyStatePolling()
            }
        }
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

    fun skipNext() {
        val currentIndex = stateHolder.state.value.queueIndex
        val nextIndex = currentIndex + 1
        if (nextIndex < currentQueue.size) {
            val nextTrack = currentQueue[nextIndex]
            scope.launch {
                if (isExternalPlayback) router.stopExternalPlayback()
                playTrackInternal(nextTrack, nextIndex)
            }
        }
    }

    fun skipPrevious() {
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

        val currentIndex = stateHolder.state.value.queueIndex
        val prevIndex = currentIndex - 1
        if (prevIndex >= 0) {
            val prevTrack = currentQueue[prevIndex]
            scope.launch {
                if (isExternalPlayback) router.stopExternalPlayback()
                playTrackInternal(prevTrack, prevIndex)
            }
        }
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

    /** Fallback: play directly via ExoPlayer when no handler matches. */
    private fun playViaExoPlayer(track: TrackEntity, queueIndex: Int) {
        val ctrl = controller ?: return
        isExternalPlayback = false
        stopSpotifyStatePolling()

        val mediaItem = track.toMediaItem()
        ctrl.stop()
        ctrl.setMediaItems(listOf(mediaItem), 0, 0L)
        ctrl.prepare()
        ctrl.play()

        stateHolder.update {
            copy(
                currentTrack = track,
                isPlaying = true,
                position = 0L,
                queue = currentQueue,
                queueIndex = queueIndex,
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
                    // Auto-advance to next track when ExoPlayer finishes
                    if (playbackState == Player.STATE_ENDED) {
                        skipNext()
                    }
                }
            }
        })
        // Sync initial state in case service was already playing
        syncState()
        if (ctrl.isPlaying) startPositionUpdates()
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        val track = currentQueue.getOrNull(index)

        stateHolder.update {
            copy(
                currentTrack = track ?: currentTrack,
                isPlaying = ctrl.isPlaying,
                position = ctrl.currentPosition.coerceAtLeast(0L),
                duration = ctrl.duration.coerceAtLeast(0L),
                queue = currentQueue,
                queueIndex = index,
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

    /** Poll Spotify App Remote for position/state when playing externally. */
    private fun startSpotifyStatePolling() {
        spotifyStateJob?.cancel()
        spotifyStateJob = scope.launch {
            val spotify = router.getSpotifyHandler()
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

                // Auto-advance when Spotify track ends
                if (!playing && position >= duration - 1000 && duration > 0) {
                    skipNext()
                    break
                }

                delay(500)
            }
        }
    }

    private fun stopSpotifyStatePolling() {
        spotifyStateJob?.cancel()
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
