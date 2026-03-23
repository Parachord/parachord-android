package com.parachord.android.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import com.parachord.android.data.metadata.MbidEnrichmentService
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
    private val mbidEnrichment: MbidEnrichmentService,
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
    private var idleTimeoutJob: Job? = null
    /** Tracks an in-flight track advance (auto or manual skip).
     *  Prevents concurrent advances from double-skipping and lets
     *  togglePlayPause() know a transition is still in progress. */
    private var advanceJob: Job? = null

    /** Whether playback is currently managed externally (e.g. Spotify Connect). */
    private var isExternalPlayback = false

    /**
     * How long to keep the foreground service alive after external playback is paused.
     * After this timeout, the service is fully demoted and the process may be killed.
     */
    private val IDLE_TIMEOUT_MS = 5L * 60 * 1000 // 5 minutes

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
                // Eagerly warm the MusicKit WebView + JS bridge so the first
                // Apple Music play doesn't pay the ~500ms initialization cost.
                router.getAppleMusicHandler().warmUp()
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        positionUpdateJob?.cancel()
        spotifyStateJob?.cancel()
        appleMusicPollingJob?.cancel()
        idleTimeoutJob?.cancel()
        if (isExternalPlayback) sendExternalPlaybackStop()
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
        // Pre-enrich queued tracks with MBIDs so they're ready for scrobbling
        enrichQueuedTracks(tracks)
    }

    /** Insert tracks at the front of the queue (play next). */
    fun insertNext(tracks: List<TrackEntity>) {
        queueManager.insertNext(tracks)
        syncQueueState()
        // Pre-enrich queued tracks with MBIDs so they're ready for scrobbling
        enrichQueuedTracks(tracks)
    }

    /** Fire MBID mapper lookups for tracks entering the queue. */
    private fun enrichQueuedTracks(tracks: List<TrackEntity>) {
        val requests = tracks
            .filter { it.recordingMbid == null }
            .map { com.parachord.android.data.metadata.TrackEnrichmentRequest(it.id, it.artist, it.title) }
        if (requests.isNotEmpty()) {
            mbidEnrichment.enrichBatchInBackground(requests)
        }
    }

    fun skipNext() {
        skipNextInternal(userInitiated = true)
    }

    private fun skipNextInternal(userInitiated: Boolean) {
        // If a previous advance is still in-flight (resolver/routing not finished),
        // ignore non-user auto-advance signals to prevent double-skipping.
        if (!userInitiated && advanceJob?.isActive == true) {
            Log.d(TAG, "skipNext: ignoring auto-advance — previous advance still in-flight")
            return
        }

        if (userInitiated) {
            onUserPlaybackActionListener?.invoke()
        } else {
            onTrackEndedListener?.invoke()
        }

        // Cancel any in-flight advance — user skip takes priority
        advanceJob?.cancel()

        // Spinoff mode: pull from separate pool, bypass queue entirely (desktop behavior)
        if (stateHolder.state.value.spinoffMode) {
            if (spinoffPool.isNotEmpty()) {
                val next = spinoffPool.removeAt(0)
                Log.d(TAG, "Spinoff: playing next '${next.title}' by ${next.artist} (${spinoffPool.size} remaining)")
                advanceJob = scope.launch {
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
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()
                scope.launch { router.stopExternalPlayback() }
                sendExternalPlaybackStop()
                isExternalPlayback = false
            }
            stateHolder.update { copy(isPlaying = false) }
            return
        }
        Log.d(TAG, "skipNext: advancing to '${next.title}' by ${next.artist}")
        advanceJob = scope.launch {
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
        advanceJob?.cancel()
        advanceJob = scope.launch {
            if (isExternalPlayback) router.stopExternalPlayback()
            playTrackInternal(prev)
        }
    }

    /** User tapped a track in the queue UI — play from that point. */
    fun playFromQueue(index: Int) {
        val currentTrack = stateHolder.state.value.currentTrack
        val track = queueManager.playFromQueue(index, currentTrack) ?: return
        advanceJob?.cancel()
        advanceJob = scope.launch {
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
                // If an advance is in-flight (auto-advance resolver/routing still running),
                // wait for it to complete rather than resuming the stale previous handler.
                val pendingAdvance = advanceJob
                if (pendingAdvance?.isActive == true) {
                    Log.d(TAG, "togglePlayPause: advance in-flight, waiting for it to complete")
                    pendingAdvance.join()
                    // After the advance completes, playback should already be started.
                    // If it's not playing (advance failed), fall through to re-play.
                    val handler = router.activeExternalHandler
                    val playing = when (handler) {
                        is AppleMusicPlaybackHandler -> handler.isPlaying()
                        null -> false
                        else -> router.getSpotifyHandler().isPlaying()
                    }
                    if (playing) return@launch
                    // Advance completed but playback didn't start — fall through to re-play
                }

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
                    // Stop polling and release WakeLock — no need to keep the CPU
                    // awake at 500ms intervals while paused.
                    stopSpotifyStatePolling()
                    stopAppleMusicStatePolling()
                    // Demote from foreground and start idle timeout
                    sendExternalPlaybackStop()
                    startIdleTimeout()
                } else {
                    // Cancel idle timeout — user is resuming
                    cancelIdleTimeout()
                    handler.resume()
                    stateHolder.update { copy(isPlaying = true) }
                    // Re-promote to foreground and restart state polling
                    val track = stateHolder.state.value.currentTrack
                    if (track != null) sendExternalPlaybackStart(track)
                    when (handler) {
                        is AppleMusicPlaybackHandler -> startAppleMusicStatePolling()
                        else -> startSpotifyStatePolling()
                    }
                    // If resume didn't actually start playback (stale session),
                    // re-play the track from scratch after a brief check.
                    delay(1500)
                    val stillNotPlaying = when (handler) {
                        is AppleMusicPlaybackHandler -> !handler.isPlaying()
                        else -> !router.getSpotifyHandler().isPlaying()
                    }
                    if (stillNotPlaying) {
                        val replayTrack = stateHolder.state.value.currentTrack ?: return@launch
                        Log.d(TAG, "togglePlayPause: resume failed, re-playing '${replayTrack.title}'")
                        playTrackInternal(replayTrack)
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

    private suspend fun playTrackInternal(track: TrackEntity, skipReselect: Boolean = false) {
        // Cancel any idle timeout — we're actively playing now
        cancelIdleTimeout()

        // Re-select the best resolver from cached sources before routing.
        // Stored tracks may have a stale `resolver` field from when they were first
        // added (e.g. "spotify"), but the user may now prioritize a different resolver
        // (e.g. "applemusic"). The TrackResolverCache has live resolution results
        // sorted by the user's current priority order, so we pick the best one.
        // Skipped when the user manually picks a source via the resolver dropdown.
        var routedTrack = if (skipReselect) track else reselectBestSource(track)

        // If the track has no resolver and no source URL, try resolving on-the-fly.
        // This handles ephemeral tracks (e.g. weekly playlists, recommendations)
        // that haven't been through the resolver pipeline yet.
        if (routedTrack.resolver == null && routedTrack.sourceUrl == null &&
            routedTrack.spotifyUri == null && routedTrack.spotifyId == null &&
            routedTrack.soundcloudId == null && routedTrack.appleMusicId == null
        ) {
            Log.d(TAG, "Track '${routedTrack.title}' has no source, resolving on-the-fly")
            routedTrack = resolveOnTheFly(routedTrack)
        }

        val action = router.route(routedTrack)
        val snapshot = queueManager.snapshot.value

        if (action == null) {
            Log.w(TAG, "No playback handler for: ${routedTrack.title}")
            playViaExoPlayer(routedTrack)
            return
        }

        when (action) {
            is PlaybackAction.ExoPlayerItem -> {
                if (isExternalPlayback) sendExternalPlaybackStop()
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
                        streamingMetadata = null, // ExoPlayer = no external source mismatch
                    )
                }
            }

            is PlaybackAction.ExternalPlayback -> {
                isExternalPlayback = true
                stopPositionUpdates()
                // Cancel old polling jobs WITHOUT releasing the wake lock — we need
                // continuous CPU wakefulness across the track transition. The new
                // startPolling() call below will take over wake lock ownership.
                // Releasing here would create a gap during handler.play() (a suspend
                // call) where Android can suspend the process.
                spotifyStateJob?.cancel()
                appleMusicPollingJob?.cancel()
                router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null

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
                        streamingMetadata = null, // Reset until polling confirms actual metadata
                    )
                }

                action.handler.play(routedTrack)

                // Promote the service to foreground so Android doesn't kill it
                sendExternalPlaybackStart(routedTrack)

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

        // Enrich with MusicBrainz MBIDs in the background
        mbidEnrichment.enrichInBackground(routedTrack.id, routedTrack.artist, routedTrack.title)

        // Pre-resolve the next few queue tracks so their resolver IDs
        // (spotifyId, appleMusicId, etc.) are ready before we need them.
        // This eliminates resolver latency from track transitions.
        val upcoming = snapshot.upNext.take(3)
        if (upcoming.isNotEmpty()) {
            trackResolverCache.resolveInBackground(upcoming)
        }

        // Check spinoff availability for the new track (unless in spinoff mode)
        if (!stateHolder.state.value.spinoffMode) {
            checkSpinoffAvailability()
        }
    }

    /** Fallback: play directly via ExoPlayer when no handler matches. */
    private fun playViaExoPlayer(track: TrackEntity) {
        val ctrl = controller ?: return
        if (isExternalPlayback) sendExternalPlaybackStop()
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
                streamingMetadata = null, // ExoPlayer = no external source mismatch
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
                    stateHolder.update {
                        copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                    }
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

                // Build streaming metadata from what Spotify reports is actually playing
                val streamingMeta = buildStreamingMetadata(
                    queuedTrack = stateHolder.state.value.currentTrack,
                    actualTitle = spotify.actualTitle,
                    actualArtist = spotify.actualArtist,
                    actualAlbum = spotify.actualAlbum,
                    actualArtworkUrl = spotify.actualArtworkUrl,
                )

                stateHolder.update {
                    copy(
                        isPlaying = playing,
                        isBuffering = spotify.isConnectionStalled,
                        position = position,
                        duration = duration,
                        streamingMetadata = streamingMeta,
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
            // Wake lock is NOT released here — it's managed by stopSpotifyStatePolling()
            // (explicit stop) or by playTrackInternal() when switching to ExoPlayer.
            // Releasing in the polling loop creates a gap during track transitions where
            // Android can suspend the process before the next polling loop starts.
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

        // Register track-ended callback from MusicKit JS.
        // On spotty networks MusicKit can fire "ended" when it fails to buffer
        // mid-song. Guard against premature advancement by cross-checking the
        // reported position against the known track duration — only accept the
        // signal if we're genuinely near the end (within 15 seconds) or if we
        // have no duration data to compare against.
        handler.musicKitBridge.onTrackEnded = {
            val position = handler.getPosition()
            val duration = handler.getDuration()
            val playing = handler.isPlaying()
            val knownDuration = stateHolder.state.value.currentTrack?.duration
            val effectiveDuration = when {
                knownDuration != null && knownDuration > 0 -> knownDuration
                duration > 0 -> duration
                else -> null
            }
            // MusicKit resets position to 0 when a track genuinely ends.
            // A position-reset with !isPlaying is a real end, not a mid-song stall
            // (stalls leave position > 0 mid-song).
            val positionReset = position == 0L && !playing
            val nearEnd = positionReset || effectiveDuration == null || effectiveDuration - position < 15_000
            if (nearEnd && trackEndHandled.compareAndSet(false, true)) {
                Log.d(TAG, "Apple Music track ended (JS callback, pos=$position dur=$effectiveDuration playing=$playing)")
                scope.launch(Dispatchers.Main) { skipNextInternal(userInitiated = false) }
            } else if (!nearEnd) {
                Log.w(TAG, "Ignoring spurious track-ended signal (pos=$position dur=$effectiveDuration) — likely network stall")
            }
        }

        // Run on Dispatchers.Default so Doze mode doesn't throttle the polling
        // loop — Dispatchers.Main gets deferred when the screen is off.
        appleMusicPollingJob = scope.launch(Dispatchers.Default) {
            try {
                // Small initial delay to let the track start
                delay(1000)

                // Stall recovery state — tracks consecutive stalled polls and
                // uses exponential backoff between resume attempts.
                var stallCount = 0
                var lastRecoveryAttempt = 0L
                val maxRecoveryBackoffMs = 16_000L
                // Prefetch: preload the next track's catalog data once we're
                // within 30 seconds of the end of the current track.
                var nextTrackPreloaded = false

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
                    val stateName = handler.musicKitBridge.playbackStateName

                    // Build streaming metadata from what MusicKit reports is actually playing
                    val streamingMeta = buildStreamingMetadata(
                        queuedTrack = stateHolder.state.value.currentTrack,
                        actualTitle = handler.musicKitBridge.actualTitle,
                        actualArtist = handler.musicKitBridge.actualArtist,
                        actualAlbum = handler.musicKitBridge.actualAlbum,
                        actualArtworkUrl = handler.musicKitBridge.actualArtworkUrl,
                    )

                    val isStalled = stateName == "stalled" || stateName == "loading"
                    stateHolder.update {
                        copy(
                            isPlaying = playing,
                            isBuffering = isStalled,
                            position = position,
                            duration = duration,
                            streamingMetadata = streamingMeta,
                        )
                    }

                    // ── Stall recovery ──────────────────────────────────────
                    // MusicKit reports "stalled" when the buffer runs dry on
                    // spotty networks. Attempt to resume with exponential
                    // backoff so we pick up where we left off once the network
                    // recovers, rather than sitting silent.
                    if (stateName == "stalled" && position > 0) {
                        stallCount++
                        val now = System.currentTimeMillis()
                        // Backoff: 2s, 4s, 8s, 16s (capped)
                        val backoffMs = (2_000L * (1L shl (stallCount - 1).coerceAtMost(3)))
                            .coerceAtMost(maxRecoveryBackoffMs)
                        if (now - lastRecoveryAttempt >= backoffMs) {
                            lastRecoveryAttempt = now
                            Log.d(TAG, "Apple Music stalled (count=$stallCount), attempting recovery at pos=$position")
                            withContext(Dispatchers.Main) {
                                try {
                                    handler.seekTo(position)
                                    handler.resume()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Stall recovery attempt failed", e)
                                }
                            }
                        }
                    } else if (stateName == "playing") {
                        // Reset stall tracking once playback resumes
                        if (stallCount > 0) {
                            Log.d(TAG, "Apple Music recovered from stall after $stallCount polls")
                            stateHolder.update { copy(isBuffering = false) }
                        }
                        stallCount = 0
                        lastRecoveryAttempt = 0L
                    }

                    // ── Next-track prefetch ──────────────────────────────────
                    // When within 30s of the end, preload the next Apple Music
                    // track's catalog data so setQueue() is near-instant.
                    // Fire-and-forget: the preload awaits a network request to
                    // Apple Music's catalog API which can take seconds. Running
                    // it inline was freezing the polling loop, blocking both the
                    // safety-net track completion check and position updates.
                    if (!nextTrackPreloaded && duration > 0 && duration - position in 1..30_000) {
                        val nextTrack = queueManager.snapshot.value.upNext.firstOrNull()
                        if (nextTrack != null) {
                            nextTrackPreloaded = true
                            scope.launch(Dispatchers.Main) {
                                val nextAmId = reselectBestSource(nextTrack).appleMusicId
                                if (nextAmId != null) {
                                    Log.d(TAG, "Preloading next Apple Music track: $nextAmId")
                                    handler.musicKitBridge.preload(nextAmId)
                                }
                            }
                        }
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
            } finally {
                // Wake lock is NOT released here — it's managed by
                // stopAppleMusicStatePolling() (explicit stop) or by
                // playTrackInternal() when switching to ExoPlayer.
                // Releasing in the polling loop creates a gap during track
                // transitions where Android can suspend the process.
            }
        }
    }

    private fun stopAppleMusicStatePolling() {
        appleMusicPollingJob?.cancel()
        releaseExternalPlaybackWakeLock()
        // Clear the callback to avoid stale references
        router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null
    }

    /**
     * After pausing external playback, start a timeout that will fully clean up
     * the external playback state if the user doesn't resume within [IDLE_TIMEOUT_MS].
     * This prevents the app from lingering indefinitely in a paused-but-connected state.
     */
    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            Log.d(TAG, "Idle timeout reached — cleaning up paused external playback")
            isExternalPlayback = false
            router.stopExternalPlayback()
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
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

    /**
     * Tell [PlaybackService] to promote itself to foreground with a persistent
     * notification. This prevents Android from killing the process during
     * external playback (Spotify/Apple Music) when the screen is off.
     */
    private fun sendExternalPlaybackStart(track: TrackEntity) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_EXTERNAL_PLAYBACK_START
            putExtra(PlaybackService.EXTRA_TRACK_TITLE, track.title)
            putExtra(PlaybackService.EXTRA_TRACK_ARTIST, track.artist)
            putExtra(PlaybackService.EXTRA_TRACK_ARTWORK_URL, track.artworkUrl)
        }
        context.startService(intent)
    }

    /**
     * Tell [PlaybackService] to demote from foreground when external playback ends.
     */
    private fun sendExternalPlaybackStop() {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_EXTERNAL_PLAYBACK_STOP
        }
        context.startService(intent)
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
                        val sources = resolverManager.resolve(
                            query,
                            targetTitle = similar.name,
                            targetArtist = artistName,
                        )
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

    /**
     * Switch the currently playing track to a different resolver source.
     * Called when the user manually selects a different source from the Now Playing
     * resolver dropdown (matching the desktop's source switcher behavior).
     * Restarts playback from 0:00 using the chosen resolver.
     */
    fun switchSource(resolver: String) {
        val track = stateHolder.state.value.currentTrack ?: return
        scope.launch(Dispatchers.Main) {
            val key = trackKey(track.title, track.artist)
            val cachedSources = trackResolverCache.trackSources.value[key]
            if (cachedSources.isNullOrEmpty()) return@launch

            val source = cachedSources.firstOrNull { it.resolver == resolver } ?: return@launch

            Log.d(TAG, "Manual source switch for '${track.title}': ${track.resolver} → $resolver")
            // Clear all resolver-specific fields, then set only the chosen resolver's.
            // Without this, leftover spotifyUri/spotifyId from a previous resolver causes
            // the router to match SpotifyPlaybackHandler even when switching to localfiles.
            val switched = track.copy(
                resolver = source.resolver,
                sourceType = source.sourceType,
                sourceUrl = source.url,
                spotifyUri = source.spotifyUri,
                spotifyId = source.spotifyId,
                soundcloudId = source.soundcloudId,
                appleMusicId = source.appleMusicId,
            )
            // skipReselect=true: the user explicitly chose this source — don't let
            // reselectBestSource() override it back to the auto-selected best.
            playTrackInternal(switched, skipReselect = true)
        }
    }

    /**
     * Resolve an unresolved track on-the-fly at play time.
     * Used for ephemeral tracks (weekly playlists, recommendations) that haven't
     * been through the resolver pipeline yet. Caches the result in TrackResolverCache
     * so subsequent plays and queue items benefit.
     */
    private suspend fun resolveOnTheFly(track: TrackEntity): TrackEntity {
        return try {
            val query = "${track.title} ${track.artist}"
            val sources = resolverManager.resolve(
                query,
                targetTitle = track.title,
                targetArtist = track.artist,
            )
            if (sources.isEmpty()) {
                Log.w(TAG, "No resolver results for '${track.title}' by ${track.artist}")
                return track
            }

            // Cache the results so reselectBestSource works for queue tracks too
            trackResolverCache.putSources(track.title, track.artist, sources)

            val best = resolverScoring.selectBest(sources) ?: return track
            Log.d(TAG, "On-the-fly resolved '${track.title}' → ${best.resolver}")
            track.copy(
                resolver = best.resolver,
                sourceType = best.sourceType,
                sourceUrl = best.url,
                spotifyUri = best.spotifyUri,
                spotifyId = best.spotifyId,
                soundcloudId = best.soundcloudId,
                appleMusicId = best.appleMusicId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "On-the-fly resolution failed for '${track.title}'", e)
            track
        }
    }

    /**
     * Build [StreamingMetadata] from what the streaming source reports is actually playing.
     * The Now Playing screen uses this to display actual track info (title, artist, album,
     * artwork) directly from the source — so the user always sees what's really streaming.
     *
     * Returns null only when actual metadata isn't available yet (polling hasn't started).
     */
    private fun buildStreamingMetadata(
        queuedTrack: TrackEntity?,
        actualTitle: String?,
        actualArtist: String?,
        actualAlbum: String?,
        actualArtworkUrl: String?,
    ): StreamingMetadata? {
        if (queuedTrack == null || actualTitle == null) return null

        return StreamingMetadata(
            title = actualTitle,
            artist = actualArtist,
            album = actualAlbum,
            artworkUrl = actualArtworkUrl,
        )
    }

    private fun enrichArtworkIfMissing(track: TrackEntity) {
        // Obviously missing — go straight to enrichment
        if (track.artworkUrl.isNullOrBlank()) {
            fetchAndApplyArtwork(track)
            return
        }
        // Local albumart content URI — might be broken, validate on IO thread
        if (track.artworkUrl.startsWith("content://media/external/audio/albumart")) {
            scope.launch(Dispatchers.IO) {
                if (isStaleLocalArtwork(track.artworkUrl)) {
                    fetchAndApplyArtwork(track)
                }
            }
            return
        }
        // Has a real URL — nothing to do
    }

    private fun fetchAndApplyArtwork(track: TrackEntity) {
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

    /**
     * Check if a local file's album art content URI is broken/empty.
     * MediaStore albumart URIs can exist as strings but point to no actual image data.
     * Returns true if the URI looks like a local albumart URI that doesn't resolve.
     */
    private fun isStaleLocalArtwork(artworkUrl: String?): Boolean {
        if (artworkUrl == null) return false
        if (!artworkUrl.startsWith("content://media/external/audio/albumart")) return false
        return try {
            val uri = android.net.Uri.parse(artworkUrl)
            context.contentResolver.openInputStream(uri)?.use { false } ?: true
        } catch (_: Exception) {
            true
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
