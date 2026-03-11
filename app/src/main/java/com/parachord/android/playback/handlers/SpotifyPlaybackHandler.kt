package com.parachord.android.playback.handlers

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
 * Matches the desktop app's approach: uses the Web API to control playback on
 * an active Spotify device rather than embedding a player. Prefers the local
 * phone device (type "Smartphone") over TVs and computers.
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
        /** Device type priority — prefer phone, then computer, then anything else. */
        private val DEVICE_TYPE_PRIORITY = listOf("Smartphone", "Computer")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statePollingJob: Job? = null

    // Cached playback state from Web API polling
    private var cachedIsPlaying = false
    private var cachedPosition = 0L
    private var cachedDuration = 0L
    private var _isConnected = false

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

            // Pick the best device: prefer Smartphone, then Computer, then first available
            val targetDevice = pickDevice(devices)
            Log.d(TAG, "Target device: '${targetDevice.name}' (type=${targetDevice.type})")

            // Transfer playback to the target device if it's not already active
            if (!targetDevice.isActive) {
                Log.d(TAG, "Transferring playback to '${targetDevice.name}'")
                spotifyApi.transferPlayback(auth, SpTransferRequest(deviceIds = listOf(targetDevice.id)))
                delay(500) // Give Spotify a moment to switch
            }

            val response = spotifyApi.startPlayback(auth, SpPlaybackRequest(uris = listOf(uri)))
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
            startStatePolling()
        }
    }

    override suspend fun pause() {
        withAuth { auth -> spotifyApi.pausePlayback(auth) }
        cachedIsPlaying = false
    }

    override suspend fun resume() {
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
     * Priority: active Smartphone > inactive Smartphone > active Computer > first available.
     */
    private fun pickDevice(devices: List<SpDevice>): SpDevice {
        // If a Smartphone is available, always prefer it (user is on their phone)
        for (type in DEVICE_TYPE_PRIORITY) {
            val active = devices.firstOrNull { it.type == type && it.isActive }
            if (active != null) return active
            val inactive = devices.firstOrNull { it.type == type }
            if (inactive != null) return inactive
        }
        // Fall back to whatever is active, or first available
        return devices.firstOrNull { it.isActive } ?: devices.first()
    }

    private fun startStatePolling() {
        statePollingJob?.cancel()
        statePollingJob = scope.launch {
            while (isActive) {
                try {
                    val state = fetchPlaybackState()
                    if (state != null) {
                        cachedIsPlaying = state.isPlaying
                        cachedPosition = state.progressMs ?: 0L
                        cachedDuration = state.item?.durationMs ?: 0L
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
