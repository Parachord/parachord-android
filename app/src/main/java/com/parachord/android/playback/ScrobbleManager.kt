package com.parachord.android.playback

import android.util.Log
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.scrobbler.Scrobbler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches scrobble events to all enabled scrobbler plugins.
 *
 * Mirrors the desktop app's scrobble-manager.js which loads and dispatches
 * to registered scrobbler plugins (Last.fm, ListenBrainz, Libre.fm).
 *
 * Scrobbling rules (per Last.fm / ListenBrainz spec):
 * - Send "now playing" when a track starts
 * - Send "scrobble" after listening to max(30s, min(duration/2, 240s))
 * - Only scrobble once per track play
 */
@Singleton
class ScrobbleManager @Inject constructor(
    private val settingsStore: SettingsStore,
    private val stateHolder: PlaybackStateHolder,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val trackDao: TrackDao,
    private val mbidEnrichment: MbidEnrichmentService,
) {
    companion object {
        private const val TAG = "ScrobbleManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    // Track state for scrobble threshold logic
    private var currentTrackId: String? = null
    private var nowPlayingSent = false
    private var scrobbleSubmitted = false
    private var trackStartTimestamp: Long = 0

    /**
     * Start observing playback state and scrobbling when enabled.
     * Called once from PlaybackController.connect().
     */
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                stateHolder.state,
                settingsStore.scrobblingEnabled,
            ) { state, enabled ->
                Pair(state, enabled)
            }.collectLatest { (state, enabled) ->
                if (!enabled) return@collectLatest
                processPlaybackState(state)
            }
        }
    }

    private suspend fun processPlaybackState(state: PlaybackState) {
        val track = state.currentTrack ?: return

        // New track started
        if (track.id != currentTrackId) {
            currentTrackId = track.id
            nowPlayingSent = false
            scrobbleSubmitted = false
            trackStartTimestamp = System.currentTimeMillis() / 1000

            if (state.isPlaying) {
                dispatchNowPlaying(track)
            }
            return
        }

        // Track is playing — send now playing if not yet sent
        if (state.isPlaying && !nowPlayingSent) {
            dispatchNowPlaying(track)
        }

        // Check scrobble threshold
        if (state.isPlaying && !scrobbleSubmitted && state.duration > 0) {
            val positionSeconds = state.position / 1000
            val durationSeconds = state.duration / 1000
            val threshold = scrobbleThreshold(durationSeconds)

            if (positionSeconds >= threshold) {
                dispatchScrobble(track, trackStartTimestamp)
            }
        }
    }

    /**
     * Per Last.fm / ListenBrainz spec: scrobble after max(30s, min(duration/2, 240s)).
     */
    private fun scrobbleThreshold(durationSeconds: Long): Long {
        val halfDuration = durationSeconds / 2
        val fourMinutes = 240L
        val minListenTime = 30L
        return maxOf(minListenTime, minOf(halfDuration, fourMinutes))
    }

    /**
     * Re-read the track from Room to pick up MBIDs that were enriched in the background,
     * then apply canonical name fallback from the MBID mapper cache.
     * Falls back to the original track if it's ephemeral (not in Room).
     */
    private suspend fun refreshTrackMbids(
        track: com.parachord.android.data.db.entity.TrackEntity,
    ): com.parachord.android.data.db.entity.TrackEntity {
        // Re-read from Room to get backfilled MBIDs
        val dbTrack = if (track.recordingMbid != null) track else {
            try { trackDao.getById(track.id) ?: track } catch (_: Exception) { track }
        }
        // Apply canonical name fallback from the mapper cache (fixes misspelled artist/track names)
        val canonical = mbidEnrichment.getCanonicalNames(dbTrack.artist, dbTrack.title)
        return if (canonical != null) {
            dbTrack.copy(artist = canonical.first, title = canonical.second)
        } else {
            dbTrack
        }
    }

    /** Dispatch "now playing" to all enabled scrobblers. */
    private suspend fun dispatchNowPlaying(track: com.parachord.android.data.db.entity.TrackEntity) {
        nowPlayingSent = true
        // Re-read from Room — MBIDs may have been backfilled since playback started
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) {
                        scrobbler.sendNowPlaying(enrichedTrack)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: now playing failed", e)
                }
            }
        }
    }

    /** Dispatch scrobble to all enabled scrobblers. */
    private suspend fun dispatchScrobble(
        track: com.parachord.android.data.db.entity.TrackEntity,
        timestamp: Long,
    ) {
        scrobbleSubmitted = true
        // Re-read from Room — by scrobble time (30s+), MBIDs should be backfilled
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) {
                        scrobbler.submitScrobble(enrichedTrack, timestamp)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: scrobble failed", e)
                }
            }
        }
    }
}
