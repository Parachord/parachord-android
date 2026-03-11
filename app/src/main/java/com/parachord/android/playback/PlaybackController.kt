package com.parachord.android.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.parachord.android.data.db.entity.TrackEntity
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
 * Bridges the UI layer to the PlaybackService via MediaController.
 * Keeps PlaybackStateHolder in sync with actual ExoPlayer state.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: PlaybackStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionUpdateJob: Job? = null

    /** Current queue of TrackEntity objects, kept in sync with MediaController's playlist. */
    private var currentQueue: List<TrackEntity> = emptyList()

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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** Play a single track immediately (clears the queue and starts fresh). */
    fun playTrack(track: TrackEntity) {
        playQueue(listOf(track), startIndex = 0)
    }

    /** Play a list of tracks, starting at the given index. */
    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        val ctrl = controller ?: return
        currentQueue = tracks

        val mediaItems = tracks.map { it.toMediaItem() }
        ctrl.setMediaItems(mediaItems, startIndex, 0L)
        ctrl.prepare()
        ctrl.play()
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun skipNext() {
        val ctrl = controller ?: return
        if (ctrl.hasNextMediaItem()) ctrl.seekToNextMediaItem()
    }

    fun skipPrevious() {
        val ctrl = controller ?: return
        if (ctrl.currentPosition > 3000) {
            ctrl.seekTo(0)
        } else if (ctrl.hasPreviousMediaItem()) {
            ctrl.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    private fun setupPlayerListener() {
        val ctrl = controller ?: return
        ctrl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncState()
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncState()
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
                if (ctrl != null && ctrl.isPlaying) {
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
