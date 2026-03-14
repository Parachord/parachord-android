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

        val success = withAuth { auth ->
            val devices = spotifyApi.getDevices(auth).devices
            Log.d(TAG, "Devices: ${devices.map { "${it.name}(active=${it.isActive}, type=${it.type})" }}")

            if (devices.isEmpty()) {
                Log.e(TAG, "No Spotify devices available — open Spotify app first")
                return@withAuth false
            }

            // Pick a Smartphone device only — never fall back to other device types
            val targetDevice = pickDevice(devices)
            if (targetDevice == null) {
                Log.e(TAG, "No Smartphone device available — cannot play")
                return@withAuth false
            }
            Log.d(TAG, "Target device: '${targetDevice.name}' (type=${targetDevice.type})")

            // Transfer playback to the target device if it's not already active
            if (!targetDevice.isActive) {
                Log.d(TAG, "Transferring playback to '${targetDevice.name}'")
                spotifyApi.transferPlayback(auth, SpTransferRequest(deviceIds = listOf(targetDevice.id)))
                delay(500) // Give Spotify a moment to switch
            }

            // Force device_id in the play call itself — this is the key mechanism
            // from the desktop app that ensures playback goes to the right device
            // even if the transfer hasn't fully settled.
            val response = spotifyApi.startPlayback(
                auth,
                SpPlaybackRequest(uris = listOf(uri)),
                deviceId = targetDevice.id,
            )
            if (response.isSuccessful) {
                Log.d(TAG, "Playback started on '${targetDevice.name}' for $uri")
                true
            } else {
                Log.e(TAG, "Start playback failed: ${response.code()}")
                false
            }
        }

        if (success == true) {
            _isConnected = true
            cachedIsPlaying = true
            cachedPosition = 0L
            cachedDuration = track.duration ?: 0L
            expectedTrackId = track.spotifyUri?.substringAfterLast(":")
            currentItemId = expectedTrackId
            playStartedAt = System.currentTimeMillis()
            pausedByUs = false
            startStatePolling()
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
     * On Android we ONLY play on Smartphone devices — never computers, TVs, or speakers.
     * Returns null if no Smartphone device is available.
     *
     * 1. Filter out restricted devices
     * 2. Match THIS phone by device name (Build.MODEL or MANUFACTURER + MODEL)
     * 3. Any Smartphone-type device
     */
    private fun pickDevice(devices: List<SpDevice>): SpDevice? {
        val controllable = devices.filter { !it.isRestricted }
        val available = controllable.ifEmpty { devices }

        Log.d(TAG, "pickDevice: localModel='${Build.MODEL}', manufacturer='${Build.MANUFACTURER}', " +
                "devices=${available.map { "'${it.name}'(type=${it.type})" }}")

        // Best match: this exact phone by name
        val localModel = Build.MODEL
        val localFull = "${Build.MANUFACTURER} ${Build.MODEL}"
        available.firstOrNull {
            it.type == "Smartphone" && (
                it.name.contains(localModel, ignoreCase = true) ||
                it.name.contains(localFull, ignoreCase = true)
            )
        }?.let { return it }

        // Any Smartphone device
        available.firstOrNull { it.type == "Smartphone" }?.let { return it }

        Log.e(TAG, "No Smartphone device found. Available: " +
                available.joinToString { "'${it.name}'(${it.type})" } +
                " — ensure Spotify is open on this phone.")
        return null
    }

    /**
     * Detect whether the track we started has finished playing.
     *
     * Handles four end-of-track scenarios:
     * 1. Spotify stopped naturally (isPlaying=false, position near duration)
     * 2. Spotify autoplay kicked in (playing a different track than expected)
     * 3. Spotify cleared the item (isPlaying=false, item=null)
     * 4. Spotify stopped and we didn't pause it (catch-all for tracks that end
     *    without position reaching duration exactly)
     */
    fun isOurTrackDone(): Boolean {
        val expected = expectedTrackId ?: return false
        val current = currentItemId

        // Spotify auto-advanced to a different track
        if (current != null && current != expected) {
            Log.d(TAG, "Track changed: expected=$expected, current=$current (autoplay)")
            return true
        }

        // Track ended naturally — not playing and position near end
        if (!cachedIsPlaying && cachedDuration > 0 && cachedPosition >= cachedDuration - 1500) {
            Log.d(TAG, "Track ended naturally: pos=$cachedPosition, dur=$cachedDuration")
            return true
        }

        // Spotify cleared the item entirely (no track playing)
        if (!cachedIsPlaying && current == null && cachedPosition > 0) {
            Log.d(TAG, "Track cleared: no current item, was playing pos=$cachedPosition")
            return true
        }

        // Spotify stopped but we didn't pause — track likely ended. This catches
        // cases where the API position doesn't reach exactly near duration.
        if (!cachedIsPlaying && !pausedByUs && cachedPosition > 0) {
            Log.d(TAG, "Track stopped (not by us): pos=$cachedPosition, dur=$cachedDuration")
            return true
        }

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
