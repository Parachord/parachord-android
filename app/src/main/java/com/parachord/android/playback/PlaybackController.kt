package com.parachord.android.playback

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.playback.handlers.AppleMusicPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.android.widget.MiniPlayerWidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val lastFmApi: LastFmApi,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val trackResolverCache: TrackResolverCache,
    private val widgetUpdater: MiniPlayerWidgetUpdater,
) {
    companion object {
        private const val TAG = "PlaybackController"
        private const val SPINOFF_SIMILAR_LIMIT = 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var spotifyStateJob: Job? = null
    private var appleMusicPollingJob: Job? = null

    /** Whether playback is currently managed externally (e.g. Spotify Connect). */
    private var isExternalPlayback = false

    /**
     * Partial WakeLock held during external playback (Spotify/Apple Music) to keep
     * the CPU active for state polling and auto-advance detection when the screen is off.
     * ExoPlayer manages its own WakeLock internally.
     */
    private val wakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Parachord::ExternalPlayback")
            .apply { setReferenceCounted(false) }
    }

    /** Listener called when a track naturally completes (auto-advance, not user skip). */
    var onTrackEndedListener: (() -> Unit)? = null

    /** Listener called when the user manually changes playback (skip, play different track). */
    var onUserPlaybackActionListener: (() -> Unit)? = null

    // ── Spinoff state ──────────────────────────────────────────────────────
    /** Separate pool of resolved similar tracks (NOT in the queue — desktop behavior). */
    private val spinoffPool = mutableListOf<TrackEntity>()
    /** The track that was playing when spinoff was activated. */
    private var spinoffSourceTrack: TrackEntity? = null
    /** Previous playback context to restore on exit (queue itself is never modified). */
    private var preSpinoffContext: PlaybackContext? = null
    private var spinoffJob: Job? = null

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
                widgetUpdater.startObserving()
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        positionUpdateJob?.cancel()
        spotifyStateJob?.cancel()
        appleMusicPollingJob?.cancel()
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

        // Spinoff mode: pull from separate pool, bypass queue entirely (desktop behavior)
        if (stateHolder.state.value.spinoffMode) {
            if (spinoffPool.isNotEmpty()) {
                val next = spinoffPool.removeAt(0)
                Log.d(TAG, "Spinoff: playing next '${next.title}' by ${next.artist} (${spinoffPool.size} remaining)")
                scope.launch {
                    if (isExternalPlayback) router.stopExternalPlayback()
                    playTrackInternal(next)
                }
                return
            } else {
                // Pool exhausted — exit spinoff, fall through to regular queue
                Log.d(TAG, "Spinoff: pool exhausted, returning to queue")
                exitSpinoff()
            }
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
            val position = when (val handler = router.activeExternalHandler) {
                is AppleMusicPlaybackHandler -> handler.getPosition()
                else -> router.getSpotifyHandler().getPosition()
            }
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
                val handler = router.activeExternalHandler
                if (handler == null) {
                    // No active handler — external playback stalled. Re-play the current track.
                    val track = stateHolder.state.value.currentTrack ?: return@launch
                    Log.d(TAG, "togglePlayPause: no active handler, re-playing '${track.title}'")
                    playTrackInternal(track)
                    return@launch
                }
                val isCurrentlyPlaying = when (handler) {
                    is AppleMusicPlaybackHandler -> handler.isPlaying()
                    else -> router.getSpotifyHandler().isPlaying()
                }
                if (isCurrentlyPlaying) {
                    handler.pause()
                    stateHolder.update { copy(isPlaying = false) }
                } else {
                    handler.resume()
                    stateHolder.update { copy(isPlaying = true) }
                    // If resume didn't actually start playback (stale session),
                    // re-play the track from scratch after a brief check.
                    delay(1500)
                    val stillNotPlaying = when (handler) {
                        is AppleMusicPlaybackHandler -> !handler.isPlaying()
                        else -> !router.getSpotifyHandler().isPlaying()
                    }
                    if (stillNotPlaying) {
                        val track = stateHolder.state.value.currentTrack ?: return@launch
                        Log.d(TAG, "togglePlayPause: resume failed, re-playing '${track.title}'")
                        playTrackInternal(track)
                    }
                }
            }
            return
        }

        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun toggleShuffle() {
        // Shuffle is disabled during spinoff mode (desktop behavior)
        if (stateHolder.state.value.spinoffMode) return
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
        // Re-select the best resolver from cached sources before routing.
        // Stored tracks may have a stale `resolver` field from when they were first
        // added (e.g. "spotify"), but the user may now prioritize a different resolver
        // (e.g. "applemusic"). The TrackResolverCache has live resolution results
        // sorted by the user's current priority order, so we pick the best one.
        val routedTrack = reselectBestSource(track)
        val action = router.route(routedTrack)
        val snapshot = queueManager.snapshot.value

        if (action == null) {
            Log.w(TAG, "No playback handler for: ${routedTrack.title}")
            playViaExoPlayer(routedTrack)
            return
        }

        when (action) {
            is PlaybackAction.ExoPlayerItem -> {
                isExternalPlayback = false
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()

                val ctrl = controller ?: return
                ctrl.stop()
                ctrl.setMediaItems(listOf(action.mediaItem), 0, 0L)
                ctrl.prepare()
                ctrl.play()

                stateHolder.update {
                    copy(
                        currentTrack = routedTrack,
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
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()

                // Don't stop ExoPlayer entirely — set track metadata so the
                // MediaSession stays active and the foreground notification persists.
                // Without this, Android kills the process when the screen is off
                // because there's no foreground service keeping it alive.
                controller?.let { ctrl ->
                    ctrl.stop()
                    val metadataItem = MediaItem.Builder()
                        .setMediaId(routedTrack.id)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(routedTrack.title)
                                .setArtist(routedTrack.artist)
                                .setAlbumTitle(routedTrack.album)
                                .build()
                        )
                        .build()
                    ctrl.setMediaItems(listOf(metadataItem))
                    ctrl.prepare()
                    // Don't call ctrl.play() — external handler manages actual playback
                }

                // Set UI state optimistically — show the track as playing immediately
                // so the user gets instant feedback. The state polling will correct
                // this if playback actually fails.
                stateHolder.update {
                    copy(
                        currentTrack = routedTrack,
                        isPlaying = true,
                        position = 0L,
                        duration = routedTrack.duration ?: 0L,
                        upNext = snapshot.upNext,
                        playbackContext = snapshot.playbackContext,
                        shuffleEnabled = snapshot.shuffleEnabled,
                    )
                }

                action.handler.play(routedTrack)

                // Start the appropriate state polling based on the handler type
                when (action.handler) {
                    is AppleMusicPlaybackHandler ->
                        startAppleMusicStatePolling()
                    else ->
                        startSpotifyStatePolling()
                }
            }
        }

        // If the track has no artwork, try to fetch it in the background
        enrichArtworkIfMissing(routedTrack)

        // Check spinoff availability for the new track (unless in spinoff mode)
        if (!stateHolder.state.value.spinoffMode) {
            checkSpinoffAvailability()
        }
    }

    /** Fallback: play directly via ExoPlayer when no handler matches. */
    private fun playViaExoPlayer(track: TrackEntity) {
        val ctrl = controller ?: return
        isExternalPlayback = false
        stopSpotifyStatePolling()
        stopAppleMusicStatePolling()

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
        acquireExternalPlaybackWakeLock()
        // Run on Dispatchers.Default so Doze mode doesn't throttle the polling
        // loop — Dispatchers.Main gets deferred when the screen is off.
        spotifyStateJob = scope.launch(Dispatchers.Default) {
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
                    withContext(Dispatchers.Main) {
                        skipNextInternal(userInitiated = false)
                    }
                    break
                }

                delay(500)
            }
        }
    }

    private fun stopSpotifyStatePolling() {
        spotifyStateJob?.cancel()
        releaseExternalPlaybackWakeLock()
    }

    /** Poll Apple Music (MusicKit JS) for position/state when playing externally. */
    private fun startAppleMusicStatePolling() {
        appleMusicPollingJob?.cancel()
        acquireExternalPlaybackWakeLock()
        val handler = router.getAppleMusicHandler()

        // Guard against multiple end-of-track signals arriving from different
        // detection paths (JS playbackStateDidChange, JS mediaItemDidEndPlaying,
        // or the polling safety net). Without this, concurrent signals would
        // call skipNextInternal() multiple times, skipping tracks in the queue.
        val trackEndHandled = java.util.concurrent.atomic.AtomicBoolean(false)

        // Register track-ended callback from MusicKit JS
        handler.musicKitBridge.onTrackEnded = {
            if (trackEndHandled.compareAndSet(false, true)) {
                Log.d(TAG, "Apple Music track ended (JS callback)")
                scope.launch(Dispatchers.Main) { skipNextInternal(userInitiated = false) }
            }
        }

        // Run on Dispatchers.Default so Doze mode doesn't throttle the polling
        // loop — Dispatchers.Main gets deferred when the screen is off.
        appleMusicPollingJob = scope.launch(Dispatchers.Default) {
            // Small initial delay to let the track start
            delay(1000)
            while (isActive && isExternalPlayback) {
                // Actively poll JS for fresh position/duration — the
                // playbackStateDidChange event only fires on state transitions,
                // not continuously during playback.
                withContext(Dispatchers.Main) {
                    handler.musicKitBridge.pollPlaybackState()
                }

                val position = handler.getPosition()
                val duration = handler.getDuration()
                val playing = handler.isPlaying()

                stateHolder.update {
                    copy(
                        isPlaying = playing,
                        position = position,
                        duration = duration,
                    )
                }

                // Safety-net track completion detection
                if (handler.isOurTrackDone() && trackEndHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Apple Music track done (safety net)")
                    withContext(Dispatchers.Main) {
                        skipNextInternal(userInitiated = false)
                    }
                    break
                }

                delay(500)
            }
        }
    }

    private fun stopAppleMusicStatePolling() {
        appleMusicPollingJob?.cancel()
        releaseExternalPlaybackWakeLock()
        // Clear the callback to avoid stale references
        router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null
    }

    private fun acquireExternalPlaybackWakeLock() {
        if (!wakeLock.isHeld) {
            // 1-hour timeout as a safety net — released explicitly when polling stops
            wakeLock.acquire(60 * 60 * 1000L)
            Log.d(TAG, "Acquired WakeLock for external playback polling")
        }
    }

    private fun releaseExternalPlaybackWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "Released WakeLock for external playback polling")
        }
    }

    // ── Spinoff public API ────────────────────────────────────────────────

    /**
     * Start spinoff mode: fetch similar tracks for the current track from
     * Last.fm, shuffle them, resolve each one, and begin playing from the pool.
     * Matches the desktop's startSpinoff() logic.
     */
    fun startSpinoff() {
        val track = stateHolder.state.value.currentTrack ?: return
        if (stateHolder.state.value.spinoffMode) return // already active

        spinoffJob?.cancel()
        stateHolder.update { copy(spinoffLoading = true) }

        spinoffJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch similar tracks from Last.fm
                val response = lastFmApi.getSimilarTracks(
                    track = track.title,
                    artist = track.artist,
                    apiKey = BuildConfig.LASTFM_API_KEY,
                    limit = SPINOFF_SIMILAR_LIMIT,
                )
                val similarTracks = response.similartracks?.track ?: emptyList()

                if (similarTracks.isEmpty()) {
                    Log.d(TAG, "Spinoff: no similar tracks found for '${track.title}'")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No similar tracks found for \"${track.title}\"", Toast.LENGTH_SHORT).show()
                    }
                    stateHolder.update { copy(spinoffLoading = false, spinoffAvailable = false) }
                    return@launch
                }

                Log.d(TAG, "Spinoff: found ${similarTracks.size} similar tracks, resolving...")

                // 2. Convert to TrackEntities and resolve each
                // Skip Last.fm images — they're almost always placeholder/blank.
                // Album art will be enriched via metadata providers on playback.
                val resolvedTracks = mutableListOf<TrackEntity>()
                for (similar in similarTracks.shuffled()) {
                    val artistName = similar.artist?.name ?: continue
                    val query = "${similar.name} ${artistName}"
                    try {
                        val sources = resolverManager.resolve(query)
                        val best = resolverScoring.selectBest(sources) ?: continue

                        resolvedTracks.add(
                            TrackEntity(
                                id = "spinoff_${similar.name}_${artistName}".hashCode().toString(),
                                title = similar.name,
                                artist = artistName,
                                sourceUrl = best.url,
                                resolver = best.resolver,
                                spotifyUri = best.spotifyUri,
                                spotifyId = best.spotifyId,
                                soundcloudId = best.soundcloudId,
                                appleMusicId = best.appleMusicId,
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Spinoff: failed to resolve '${similar.name}'", e)
                    }
                }

                if (resolvedTracks.isEmpty()) {
                    Log.d(TAG, "Spinoff: no tracks could be resolved")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No similar tracks found for \"${track.title}\"", Toast.LENGTH_SHORT).show()
                    }
                    stateHolder.update { copy(spinoffLoading = false, spinoffAvailable = false) }
                    return@launch
                }

                Log.d(TAG, "Spinoff: resolved ${resolvedTracks.size} tracks, ready for playback")

                // 3. Save previous playback context (queue is NOT modified — desktop behavior)
                preSpinoffContext = queueManager.playbackContext
                spinoffSourceTrack = track

                // 4. Populate spinoff pool (separate from queue).
                //    Don't interrupt the current song — let it finish, then
                //    skipNextInternal() will pull from the pool automatically.
                spinoffPool.clear()
                spinoffPool.addAll(resolvedTracks)

                // Set playback context to spinoff (queue contents untouched)
                queueManager.setContext(PlaybackContext(type = "spinoff", name = "Spinoff from ${track.title}"))

                stateHolder.update {
                    copy(
                        spinoffMode = true,
                        spinoffLoading = false,
                        spinoffAvailable = true,
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Spinning off of ${track.title} - ${track.artist}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Spinoff: failed to start", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to fetch similar tracks", Toast.LENGTH_SHORT).show()
                }
                stateHolder.update { copy(spinoffLoading = false) }
            }
        }
    }

    /**
     * Exit spinoff mode and restore the previous queue context.
     * Matches the desktop's exitSpinoff() logic.
     */
    fun exitSpinoff() {
        if (!stateHolder.state.value.spinoffMode) return

        spinoffJob?.cancel()
        spinoffPool.clear()
        spinoffSourceTrack = null

        // Restore previous playback context (queue was never modified)
        queueManager.setContext(preSpinoffContext)
        preSpinoffContext = null

        stateHolder.update {
            copy(
                spinoffMode = false,
                spinoffLoading = false,
            )
        }
        syncQueueState()

        Log.d(TAG, "Spinoff: exited, restored previous context")
    }

    /** Toggle spinoff on/off. */
    fun toggleSpinoff() {
        if (stateHolder.state.value.spinoffMode) {
            exitSpinoff()
        } else {
            startSpinoff()
        }
    }

    /**
     * Check whether spinoff is available for the current track.
     * Lightweight call with limit=1 — called on track change.
     */
    fun checkSpinoffAvailability() {
        val track = stateHolder.state.value.currentTrack ?: return
        // Don't check during spinoff mode
        if (stateHolder.state.value.spinoffMode) return

        scope.launch(Dispatchers.IO) {
            try {
                val response = lastFmApi.getSimilarTracks(
                    track = track.title,
                    artist = track.artist,
                    apiKey = BuildConfig.LASTFM_API_KEY,
                    limit = 1,
                )
                val available = !response.similartracks?.track.isNullOrEmpty()
                stateHolder.update { copy(spinoffAvailable = available) }
            } catch (e: Exception) {
                Log.w(TAG, "Spinoff availability check failed", e)
                stateHolder.update { copy(spinoffAvailable = null) }
            }
        }
    }

    /**
     * If the currently playing track has no artwork, try to fetch it from
     * metadata providers in the background. Updates both the DB and the
     * live PlaybackState so the UI refreshes without a restart.
     */
    /**
     * Re-select the best resolver for a track using cached resolution results.
     *
     * When a track is stored in the DB or queue, its `resolver` field reflects
     * whichever resolver was "best" at the time it was added. But the user may
     * have since changed their resolver priority order, or background resolution
     * may have discovered additional sources (e.g. Apple Music for a track that
     * was originally stored as Spotify-only).
     *
     * This checks the shared [TrackResolverCache] for live-resolved sources and
     * uses [ResolverScoring.selectBest] to pick the current best, then returns
     * a copy of the track with the updated routing fields. If no cached sources
     * exist, the original track is returned unchanged.
     */
    private suspend fun reselectBestSource(track: TrackEntity): TrackEntity {
        val key = trackKey(track.title, track.artist)
        val cachedSources = trackResolverCache.trackSources.value[key]
        if (cachedSources.isNullOrEmpty()) return track

        val best = resolverScoring.selectBest(cachedSources) ?: return track

        // Only update if the best resolver actually changed
        if (best.resolver == track.resolver) return track

        Log.d(TAG, "Re-routed '${track.title}' from ${track.resolver} → ${best.resolver} (user priority)")
        return track.copy(
            resolver = best.resolver,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            spotifyUri = best.spotifyUri ?: track.spotifyUri,
            spotifyId = best.spotifyId ?: track.spotifyId,
            soundcloudId = best.soundcloudId ?: track.soundcloudId,
            appleMusicId = best.appleMusicId ?: track.appleMusicId,
        )
    }

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
