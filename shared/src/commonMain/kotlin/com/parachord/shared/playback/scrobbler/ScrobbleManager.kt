package com.parachord.shared.playback.scrobbler

import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Minimal playback snapshot the scrobble threshold logic needs. Decouples the
 * shared [ScrobbleManager] from each platform's playback-state holder — Android
 * maps `PlaybackStateHolder.state` into this; iOS maps its queue coordinator.
 * `position` / `duration` are milliseconds (the manager converts to seconds).
 */
data class ScrobbleState(
    val currentTrack: Track?,
    val isPlaying: Boolean,
    val position: Long,
    val duration: Long,
)

/**
 * Dispatches scrobble events to all enabled scrobblers (#193, KMP-shared so iOS
 * scrobbles through the same instances as Android). Mirrors the desktop's
 * scrobble-manager.js.
 *
 * Scrobbling rules (Last.fm / ListenBrainz spec):
 * - Send "now playing" when a track starts.
 * - Send "scrobble" after max(30s, min(duration/2, 240s)).
 * - Only scrobble once per track play.
 *
 * Two platform-coupled concerns are forwarded as constructor params:
 *  - [playbackStateFlow] — the platform's playback state mapped to [ScrobbleState].
 *  - [dispatchToPlugins] — fire-and-forget hand-off to the JS-side
 *    `window.scrobbleManager` registry (achordion telemetry). Android wires the
 *    WebView/JsBridge + resolver-cache source-map here; iOS supplies its own
 *    (PluginManager) or a no-op. Kept off the shared path because building the
 *    desktop-shaped `sources` JSON depends on each platform's resolver cache.
 */
class ScrobbleManager(
    private val settingsStore: SettingsStore,
    private val playbackStateFlow: Flow<ScrobbleState>,
    private val scrobblers: Set<Scrobbler>,
    private val trackDao: TrackDao,
    private val mbidEnrichment: MbidEnrichmentService,
    private val dispatchToPlugins: (eventName: String, track: Track) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "ScrobbleManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null

    // Track state for scrobble threshold logic.
    private var currentTrackId: String? = null
    private var nowPlayingSent = false
    private var scrobbleSubmitted = false
    private var trackStartTimestamp: Long = 0

    /**
     * Start observing playback state and scrobbling when enabled. Idempotent —
     * cancels any prior observation. Called once after wiring (Android:
     * PlaybackController.connect; iOS: container init).
     */
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(playbackStateFlow, settingsStore.scrobblingEnabled) { state, enabled ->
                Pair(state, enabled)
            }.collectLatest { (state, enabled) ->
                if (!enabled) return@collectLatest
                processPlaybackState(state)
            }
        }
    }

    private suspend fun processPlaybackState(state: ScrobbleState) {
        val track = state.currentTrack ?: return

        // New track started.
        if (track.id != currentTrackId) {
            currentTrackId = track.id
            nowPlayingSent = false
            scrobbleSubmitted = false
            trackStartTimestamp = currentTimeMillis() / 1000

            if (state.isPlaying) dispatchNowPlaying(track)
            return
        }

        // Track is playing — send now playing if not yet sent.
        if (state.isPlaying && !nowPlayingSent) dispatchNowPlaying(track)

        // Check scrobble threshold.
        if (state.isPlaying && !scrobbleSubmitted && state.duration > 0) {
            val positionSeconds = state.position / 1000
            val durationSeconds = state.duration / 1000
            if (positionSeconds >= scrobbleThreshold(durationSeconds)) {
                dispatchScrobble(track, trackStartTimestamp)
            }
        }
    }

    /** Per Last.fm / ListenBrainz spec: scrobble after max(30s, min(duration/2, 240s)). */
    private fun scrobbleThreshold(durationSeconds: Long): Long =
        maxOf(30L, minOf(durationSeconds / 2, 240L))

    /**
     * Re-read the track from the DB to pick up MBIDs enriched in the background,
     * then apply canonical name fallback from the MBID mapper cache. Falls back
     * to the original track if it's ephemeral (not in the DB).
     */
    private suspend fun refreshTrackMbids(track: Track): Track {
        val dbTrack = if (track.recordingMbid != null) track else {
            try { trackDao.getById(track.id) ?: track } catch (_: Exception) { track }
        }
        val canonical = mbidEnrichment.getCanonicalNames(dbTrack.artist, dbTrack.title)
        return if (canonical != null) {
            dbTrack.copy(artist = canonical.first, title = canonical.second)
        } else {
            dbTrack
        }
    }

    /** Dispatch "now playing" to all enabled scrobblers and JS plugins. */
    private suspend fun dispatchNowPlaying(track: Track) {
        nowPlayingSent = true
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) scrobbler.sendNowPlaying(enrichedTrack)
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: now playing failed", e)
                }
            }
        }
        dispatchToPlugins("updateNowPlaying", enrichedTrack)
    }

    /** Dispatch scrobble to all enabled scrobblers and JS plugins. */
    private suspend fun dispatchScrobble(track: Track, timestamp: Long) {
        scrobbleSubmitted = true
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) scrobbler.submitScrobble(enrichedTrack, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: scrobble failed", e)
                }
            }
        }
        dispatchToPlugins("scrobble", enrichedTrack)
    }
}
