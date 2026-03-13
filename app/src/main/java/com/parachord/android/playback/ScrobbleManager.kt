package com.parachord.android.playback

import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrobbles tracks to Last.fm, mirroring the desktop app's ScrobbleManager.
 *
 * Scrobbling rules (per Last.fm spec):
 * - Send `track.updateNowPlaying` when a track starts
 * - Send `track.scrobble` after listening to max(30s, min(duration/2, 240s))
 * - Only scrobble once per track play
 *
 * Requires: Last.fm session key (from auth) + API key + shared secret.
 */
@Singleton
class ScrobbleManager @Inject constructor(
    private val settingsStore: SettingsStore,
    private val stateHolder: PlaybackStateHolder,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ScrobbleManager"
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
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
        val sessionKey = settingsStore.getLastFmSessionKey() ?: return

        // New track started
        if (track.id != currentTrackId) {
            currentTrackId = track.id
            nowPlayingSent = false
            scrobbleSubmitted = false
            trackStartTimestamp = System.currentTimeMillis() / 1000

            if (state.isPlaying) {
                sendNowPlaying(track, sessionKey)
            }
            return
        }

        // Track is playing — send now playing if not yet sent
        if (state.isPlaying && !nowPlayingSent) {
            sendNowPlaying(track, sessionKey)
        }

        // Check scrobble threshold
        if (state.isPlaying && !scrobbleSubmitted && state.duration > 0) {
            val positionSeconds = state.position / 1000
            val durationSeconds = state.duration / 1000
            val threshold = scrobbleThreshold(durationSeconds)

            if (positionSeconds >= threshold) {
                submitScrobble(track, sessionKey, trackStartTimestamp)
            }
        }
    }

    /**
     * Per Last.fm spec: scrobble after max(30s, min(duration/2, 240s)).
     */
    private fun scrobbleThreshold(durationSeconds: Long): Long {
        val halfDuration = durationSeconds / 2
        val fourMinutes = 240L
        val minListenTime = 30L
        return maxOf(minListenTime, minOf(halfDuration, fourMinutes))
    }

    private suspend fun sendNowPlaying(track: TrackEntity, sessionKey: String) {
        try {
            val params = buildMap {
                put("method", "track.updateNowPlaying")
                put("artist", track.artist)
                put("track", track.title)
                put("api_key", BuildConfig.LASTFM_API_KEY)
                put("sk", sessionKey)
                track.album?.let { put("album", it) }
                track.duration?.let { put("duration", (it / 1000).toString()) }
            }

            val success = postSigned(params)
            if (success) {
                nowPlayingSent = true
                Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send now playing", e)
        }
    }

    private suspend fun submitScrobble(track: TrackEntity, sessionKey: String, timestamp: Long) {
        try {
            val params = buildMap {
                put("method", "track.scrobble")
                put("artist[0]", track.artist)
                put("track[0]", track.title)
                put("timestamp[0]", timestamp.toString())
                put("api_key", BuildConfig.LASTFM_API_KEY)
                put("sk", sessionKey)
                track.album?.let { put("album[0]", it) }
                track.duration?.let { put("duration[0]", (it / 1000).toString()) }
            }

            val success = postSigned(params)
            if (success) {
                scrobbleSubmitted = true
                Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scrobble", e)
        }
    }

    /**
     * POST signed request to Last.fm API.
     * Signature = MD5 of sorted key+value pairs (excluding format) + shared secret.
     */
    private fun postSigned(params: Map<String, String>): Boolean {
        val allParams = params.toMutableMap()
        allParams["format"] = "json"

        // Generate api_sig: sort keys (excluding format), concat key+value, append secret, MD5
        val sigString = allParams
            .filterKeys { it != "format" }
            .toSortedMap()
            .entries
            .joinToString("") { "${it.key}${it.value}" } + BuildConfig.LASTFM_SHARED_SECRET

        allParams["api_sig"] = md5(sigString)

        val formBody = FormBody.Builder().apply {
            allParams.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder()
            .url(API_URL)
            .post(formBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()

        if (!response.isSuccessful) {
            Log.e(TAG, "Last.fm API error ${response.code}: $body")
            return false
        }

        // Check for Last.fm error codes in JSON response
        if (body != null && body.contains("\"error\"")) {
            Log.e(TAG, "Last.fm API returned error: $body")
            return false
        }

        return true
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
