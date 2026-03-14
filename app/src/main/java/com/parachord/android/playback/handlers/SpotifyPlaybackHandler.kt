package com.parachord.android.playback.handlers

import android.os.Build
import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.SpPlaybackRequest
import com.parachord.android.data.api.SpPlaybackState
import com.parachord.android.data.api.SpDevice
import com.parachord.android.data.api.SpTransferRequest
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Spotify playback via the Web API (Spotify Connect).
 *
 * Unlike the desktop app (which prefers Computer devices), the Android app
 * forces playback to THIS phone. Device selection:
 * 1. Match this device by name (Build.MODEL)
 * 2. Any Smartphone-type device
 * 3. Only if no phone is available, fall back to other device types
 *
 * Requires an active Spotify device and Premium account.
 */
@Singleton
class SpotifyPlaybackHandler @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "SpotifyPlayback"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statePollingJob: Job? = null

    // Cached playback state from Web API polling
    private var cachedIsPlaying = false
    private var cachedPosition = 0L
    private var cachedDuration = 0L
    private var _isConnected = false

    /** The Spotify track ID we expect to be playing (from the URI we sent to play). */
    private var expectedTrackId: String? = null
    /** The track ID currently reported by the Spotify API. */
    private var currentItemId: String? = null
    /** Timestamp when we started playing — used to filter stale API responses during polling. */
    private var playStartedAt: Long = 0L
    /** True when WE initiated a pause (user tapped pause). False means Spotify stopped on its own. */
    private var pausedByUs = false

    // Track previous poll state for reset-after-end detection (matches desktop pattern)
    private var lastProgressMs = 0L
    private var lastPercentComplete = 0f

    override val isConnected: Boolean get() = _isConnected

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "spotify" && track.spotifyUri != null

    override suspend fun createMediaItem(track: TrackEntity): Nothing? = null

    override suspend fun play(track: TrackEntity) {
        val uri = track.spotifyUri
        if (uri == null) {
            Log.w(TAG, "No spotifyUri on track '${track.title}'")
            return
        }
        Log.d(TAG, "play() via Web API for '${track.title}' uri=$uri")

        // Attempt playback with retry — mirrors the desktop's spotify.axe play() flow:
        // 1. Get devices
        // 2. Pick best device (desktop: computer > phone > speaker > any)
        // 3. Transfer if not active, wait for device to settle
        // 4. Start playback with device_id
        // On failure, retry the whole flow (token refresh, device re-check)
        val success = playWithRetry(track, uri)

        if (success) {
            _isConnected = true
            cachedIsPlaying = true
            cachedPosition = 0L
            cachedDuration = track.duration ?: 0L
            expectedTrackId = uri.substringAfterLast(":")
            currentItemId = expectedTrackId
            playStartedAt = System.currentTimeMillis()
            pausedByUs = false
            lastProgressMs = 0L
            lastPercentComplete = 0f
            startStatePolling()
        } else {
            Log.e(TAG, "Failed to play '${track.title}' after retries")
        }
    }

    /**
     * Attempt the full play flow with retries.
     * Each attempt re-checks devices and token freshness.
     */
    private suspend fun playWithRetry(track: TrackEntity, uri: String): Boolean {
        for (attempt in 1..3) {
            try {
                val result = withAuth { auth -> attemptPlay(auth, uri) }
                if (result == true) return true

                if (attempt < 3) {
                    Log.d(TAG, "Play attempt $attempt failed, retrying in ${attempt}s...")
                    delay(attempt * 1000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Play attempt $attempt error: ${e.message}")
                if (attempt < 3) delay(attempt * 1000L)
            }
        }
        return false
    }

    /**
     * Single attempt to find a device and start playback.
     * Mirrors the desktop's spotify.axe play() logic:
     * 1. Get devices, pick best one
     * 2. Transfer if not active (wait 1000ms like desktop)
     * 3. Start playback with device_id
     */
    private suspend fun attemptPlay(auth: String, uri: String): Boolean {
        val devices = spotifyApi.getDevices(auth).devices
        Log.d(TAG, "Devices: ${devices.map { "${it.name}(active=${it.isActive}, type=${it.type})" }}")

        if (devices.isEmpty()) {
            Log.w(TAG, "No Spotify devices available — is Spotify running?")
            return false
        }

        val targetDevice = pickDevice(devices)
        if (targetDevice == null) {
            Log.w(TAG, "No suitable device found")
            return false
        }
        Log.d(TAG, "Target device: '${targetDevice.name}' (type=${targetDevice.type}, active=${targetDevice.isActive})")

        // Transfer playback to the target device if it's not already active
        if (!targetDevice.isActive) {
            Log.d(TAG, "Transferring playback to '${targetDevice.name}'")
            try {
                spotifyApi.transferPlayback(auth, SpTransferRequest(deviceIds = listOf(targetDevice.id)))
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed (non-fatal): ${e.message}")
            }
            // Desktop waits 1000ms — give Spotify time to activate the device
            delay(1000)
        }

        // Force device_id in the play call (desktop pattern) — ensures playback
        // goes to the right device even if transfer hasn't fully settled
        val response = spotifyApi.startPlayback(
            auth,
            SpPlaybackRequest(uris = listOf(uri)),
            deviceId = targetDevice.id,
        )

        return if (response.isSuccessful || response.code() == 204) {
            Log.d(TAG, "Playback started on '${targetDevice.name}' for $uri")
            true
        } else {
            Log.w(TAG, "Start playback failed: ${response.code()}")
            false
        }
    }

    override suspend fun pause() {
        pausedByUs = true
        withAuth { auth -> spotifyApi.pausePlayback(auth) }
        cachedIsPlaying = false
    }

    override suspend fun resume() {
        pausedByUs = false
        withAuth { auth -> spotifyApi.resumePlayback(auth) }
        cachedIsPlaying = true
    }

    override suspend fun seekTo(positionMs: Long) {
        withAuth { auth -> spotifyApi.seekPlayback(auth, positionMs) }
        cachedPosition = positionMs
    }

    override suspend fun stop() {
        pause()
        stopStatePolling()
    }

    /** Get current playback position from Spotify. */
    fun getPosition(): Long = cachedPosition

    /** Get current track duration from Spotify. */
    fun getDuration(): Long = cachedDuration

    /** Whether Spotify is currently playing. */
    fun isPlaying(): Boolean = cachedIsPlaying

    fun disconnect() {
        stopStatePolling()
        _isConnected = false
    }

    /**
     * Pick the best device to play on.
     *
     * Adapted from the desktop's device selection for Android — we strongly prefer
     * THIS phone as the playback target. Remote devices (TVs, speakers, Chromecasts)
     * are excluded from automatic selection since they're typically on other networks
     * or in other rooms and shouldn't be hijacked by the phone app.
     *
     * Priority:
     * 1. THIS phone by device name (Build.MODEL)
     * 2. Any Smartphone-type device
     * 3. Any Computer-type device (e.g. tablet registered as Computer)
     * 4. Never auto-select: TV, Speaker, CastVideo, CastAudio, GameConsole
     */
    private fun pickDevice(devices: List<SpDevice>): SpDevice? {
        val controllable = devices.filter { !it.isRestricted }
        val available = controllable.ifEmpty { devices }

        val localModel = Build.MODEL
        val localFull = "${Build.MANUFACTURER} ${Build.MODEL}"
        Log.d(TAG, "pickDevice: localModel='$localModel', " +
                "devices=${available.map { "'${it.name}'(type=${it.type}, active=${it.isActive})" }}")

        // Remote device types that should NEVER be auto-selected from the phone app.
        // These are typically on other networks, in other rooms, or shared devices.
        val remoteTypes = setOf("TV", "Speaker", "CastVideo", "CastAudio", "GameConsole", "AVR")

        // Filter to local-safe devices (phones, computers, unknown types — NOT TVs/speakers)
        val localDevices = available.filter { it.type !in remoteTypes }

        // Best match: this exact phone by name (check any type — Spotify sometimes
        // reports phones as different types)
        localDevices.firstOrNull {
            it.name.contains(localModel, ignoreCase = true) ||
            it.name.contains(localFull, ignoreCase = true)
        }?.let { return it }

        // Any Smartphone device
        localDevices.firstOrNull { it.type == "Smartphone" }?.let { return it }

        // Any Computer-type device (tablet, etc.)
        localDevices.firstOrNull { it.type == "Computer" }?.let { return it }

        // Any remaining non-remote device
        localDevices.firstOrNull()?.let { return it }

        // If ONLY remote devices exist, log warning and return null —
        // don't hijack a TV/speaker the user isn't using from this phone
        Log.w(TAG, "Only remote devices available (${available.map { "${it.name}(${it.type})" }})" +
                " — not auto-selecting. Ensure Spotify is open on this phone.")
        return null
    }

    /**
     * Detect whether the track we started has finished or is about to finish.
     *
     * Matches the desktop app's approach: advance EARLY (within 2s of end)
     * while still playing, to preempt Spotify's autoplay from starting a
     * different track. This avoids the race condition where Spotify auto-advances
     * to its own recommendation before we can queue our next track.
     *
     * Detection scenarios (mirroring desktop spotifyPoller):
     * 1. Near-end: still playing, within 2s of track end → advance early
     * 2. Finished: paused, position at 98%+ of duration → normal end
     * 3. Reset-after-end: position jumped to 0 after being at 90%+ → track looped/reset
     * 4. Track changed: Spotify auto-played a different track (URI mismatch)
     * 5. Item cleared: no track playing, was playing before
     */
    fun isOurTrackDone(): Boolean {
        val expected = expectedTrackId ?: return false
        val current = currentItemId

        // 1. Near-end: advance EARLY while still playing (preempt Spotify autoplay)
        val isNearEnd = cachedDuration > 0 && cachedPosition >= cachedDuration - 2000
        if (isNearEnd && cachedIsPlaying) {
            Log.d(TAG, "Near end (advance early): pos=$cachedPosition, dur=$cachedDuration")
            return true
        }

        // 2. Finished: paused at or near end (98%+)
        val percentComplete = if (cachedDuration > 0) (cachedPosition.toFloat() / cachedDuration * 100) else 0f
        val isAtEnd = cachedDuration > 0 && cachedPosition >= cachedDuration - 100
        if (!cachedIsPlaying && (isAtEnd || percentComplete >= 98f)) {
            Log.d(TAG, "Track finished: pos=$cachedPosition, dur=$cachedDuration, pct=$percentComplete%")
            return true
        }

        // 3. Reset-after-end: position was 90%+ but now at 0 (Spotify reset after track end)
        if (!cachedIsPlaying && cachedPosition == 0L && lastProgressMs > 0 && lastPercentComplete >= 90f) {
            Log.d(TAG, "Reset after end: lastPos=$lastProgressMs, lastPct=$lastPercentComplete%")
            return true
        }

        // 4. Spotify auto-advanced to a different track
        if (current != null && current != expected) {
            Log.d(TAG, "Track changed: expected=$expected, current=$current (autoplay)")
            return true
        }

        // 5. Spotify cleared the item entirely (no track playing)
        if (!cachedIsPlaying && current == null && cachedPosition > 0) {
            Log.d(TAG, "Track cleared: no current item, was playing pos=$cachedPosition")
            return true
        }

        // Update last-known progress for reset-after-end detection
        lastProgressMs = cachedPosition
        lastPercentComplete = percentComplete

        return false
    }

    private fun startStatePolling() {
        statePollingJob?.cancel()
        statePollingJob = scope.launch {
            while (isActive) {
                try {
                    val state = fetchPlaybackState()
                    if (state != null) {
                        val apiItemId = state.item?.id
                        val elapsed = System.currentTimeMillis() - playStartedAt

                        // Guard against stale API responses during track transitions.
                        // When we just called play(), Spotify may still report the previous
                        // track's state for a few seconds. During this grace period, only
                        // accept API state that matches our expected track. This prevents
                        // stale isPlaying/position/itemId from triggering false auto-advance.
                        val isStale = elapsed < 3000 && apiItemId != null && apiItemId != expectedTrackId

                        if (isStale) {
                            Log.d(TAG, "Ignoring stale API state: item=$apiItemId (expected=$expectedTrackId, ${elapsed}ms since play)")
                        } else {
                            cachedIsPlaying = state.isPlaying
                            cachedPosition = state.progressMs ?: 0L
                            currentItemId = apiItemId

                            // Preserve duration when item becomes null (track just ended)
                            if (state.item?.durationMs != null) {
                                cachedDuration = state.item.durationMs
                            }
                        }
                    }
                } catch (_: Exception) { /* ignore polling errors */ }
                delay(1000)
            }
        }
    }

    private fun stopStatePolling() {
        statePollingJob?.cancel()
    }

    private suspend fun fetchPlaybackState(): SpPlaybackState? =
        withAuth { auth ->
            val response = spotifyApi.getPlaybackState(auth)
            if (response.isSuccessful) response.body() else null
        }

    /**
     * Execute a block with a valid Spotify Bearer token.
     * On 401, refreshes the token and retries once.
     */
    private suspend fun <T> withAuth(block: suspend (auth: String) -> T): T? {
        val token = settingsStore.getSpotifyAccessToken() ?: return null
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            if (e.code() == 401 && oAuthManager.refreshSpotifyToken()) {
                val newToken = settingsStore.getSpotifyAccessToken() ?: return null
                block("Bearer $newToken")
            } else {
                Log.e(TAG, "Spotify API error: ${e.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify API error", e)
            null
        }
    }
}
