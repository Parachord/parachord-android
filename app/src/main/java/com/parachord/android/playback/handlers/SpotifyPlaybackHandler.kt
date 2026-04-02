package com.parachord.android.playback.handlers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.SpPlaybackRequest
import com.parachord.android.data.api.SpPlaybackState
import com.parachord.android.data.api.SpDevice
import com.parachord.android.data.api.SpTransferRequest
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Spotify playback via the Web API (Spotify Connect).
 *
 * Device selection (matching desktop device picker):
 * 1. Use preferred device if set and available
 * 2. Single device: auto-select without prompting
 * 3. Multiple devices, none preferred: show device picker dialog
 * 4. Preferred device unavailable: show picker again
 *
 * Requires an active Spotify device and Premium account.
 */
@Singleton
class SpotifyPlaybackHandler @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
    @ApplicationContext private val context: Context,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "SpotifyPlayback"
        // Raised from 10 → 30 to tolerate Doze mode network delays.
        // At ~1s polling interval, 30 failures ≈ 30s of API unreachability.
        // Doze can delay network for 15+ seconds; the old threshold of 10
        // caused false track-done signals when the screen was off.
        private const val MAX_POLL_FAILURES = 30
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        /** Max time to wait for Spotify to register as a Connect device after launching.
         *  Reduced from 15s → 8s: launch intent wakes Spotify faster than the old
         *  media button broadcast, so 8s is generous even for cold starts. */
        private const val WAKE_TIMEOUT_MS = 8000L
        /** Interval between device-list polls while waiting for Spotify to wake.
         *  Reduced from 1s → 300ms: catches the device appearing sooner. The
         *  GET /v1/me/player/devices call is lightweight (~50-100ms). */
        private const val WAKE_POLL_INTERVAL_MS = 300L
        /** Initial delay before first device poll after launching Spotify.
         *  Gives Spotify 500ms to initialize before we start hammering the API. */
        private const val WAKE_INITIAL_DELAY_MS = 500L
        /** Sentinel ID for the synthetic "This device" entry in the picker. */
        const val LOCAL_DEVICE_ID = "__local_device__"
    }

    /**
     * Request for the UI to show a device picker dialog.
     * Contains the list of available devices and a deferred to complete with the user's choice.
     */
    data class DevicePickerRequest(
        val devices: List<SpDevice>,
        val deferred: CompletableDeferred<SpDevice?>,
    )

    private val _devicePickerRequest = MutableStateFlow<DevicePickerRequest?>(null)
    /** Observed by the UI to show the device picker dialog. Null when no picker is needed. */
    val devicePickerRequest: StateFlow<DevicePickerRequest?> = _devicePickerRequest

    /** Error messages for the UI to display (snackbar/toast). Null when no error. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    /** Clear the current playback error (call after displaying it). */
    fun clearPlaybackError() { _playbackError.value = null }

    /** Called by the UI when the user selects a device (or null to cancel). */
    fun onDevicePicked(device: SpDevice?) {
        val request = _devicePickerRequest.value ?: return
        request.deferred.complete(device)
        _devicePickerRequest.value = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statePollingJob: Job? = null

    // Cached playback state from Web API polling
    private var cachedIsPlaying = false
    private var cachedPosition = 0L
    private var cachedDuration = 0L
    private var _isConnected = false
    /** True after the first successful play this session — device is warm. */
    private var deviceVerified = false

    // Actual track metadata from the Spotify API (what's REALLY playing)
    /** Title of the track Spotify reports as currently playing. */
    var actualTitle: String? = null; private set
    /** Artist of the track Spotify reports as currently playing. */
    var actualArtist: String? = null; private set
    /** Album name of the track Spotify reports as currently playing. */
    var actualAlbum: String? = null; private set
    /** Album artwork URL of the track Spotify reports as currently playing. */
    var actualArtworkUrl: String? = null; private set

    /** The Spotify track ID we expect to be playing (from the URI we sent to play). */
    private var expectedTrackId: String? = null
    /** The track ID currently reported by the Spotify API. */
    private var currentItemId: String? = null
    /** Timestamp when we started playing — used to filter stale API responses during polling. */
    private var playStartedAt: Long = 0L
    /** The URI we most recently asked Spotify to play — used for retry in polling. */
    private var lastPlayedUri: String? = null
    /** True when WE initiated a pause (user tapped pause). False means Spotify stopped on its own. */
    private var pausedByUs = false
    /** True once the polling loop has confirmed playback started for the current track. */
    private var playbackConfirmed = false

    // Track previous poll state for reset-after-end detection (matches desktop pattern)
    private var lastProgressMs = 0L
    private var lastPercentComplete = 0f

    /** Consecutive state-polling failures — used for watchdog auto-advance. */
    private var consecutivePollFailures = 0
    /** Timestamp when cachedPosition last changed — used for time-based stale detection. */
    private var lastPositionChangeTime = 0L

    /** True when the connection to Spotify is experiencing sustained issues (not transient blips). */
    val isConnectionStalled: Boolean
        get() = consecutivePollFailures >= 3 ||
                (cachedIsPlaying && lastPositionChangeTime > 0 &&
                    System.currentTimeMillis() - lastPositionChangeTime > 8000)

    override val isConnected: Boolean get() = _isConnected

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "spotify" && track.spotifyUri != null

    override suspend fun createMediaItem(track: TrackEntity): Nothing? = null

    override suspend fun play(track: TrackEntity) {
        val uri = track.spotifyUri
        if (uri == null) {
            Log.w(TAG, "No spotifyUri on track '${track.title}'")
            _playbackError.value = "No Spotify URI for '${track.title}'"
            return
        }
        Log.d(TAG, "play() via Web API for '${track.title}' uri=$uri")
        _playbackError.value = null // Clear any previous error
        val flowStart = System.currentTimeMillis()

        val success = withAuth { auth -> attemptPlay(auth, uri, flowStart) } == true

        if (success) {
            _isConnected = true
            cachedIsPlaying = true
            cachedPosition = 0L
            cachedDuration = track.duration ?: 0L
            expectedTrackId = uri.substringAfterLast(":")
            currentItemId = expectedTrackId
            lastPlayedUri = uri
            playStartedAt = System.currentTimeMillis()
            pausedByUs = false
            playbackConfirmed = false
            actualTitle = null
            actualArtist = null
            actualAlbum = null
            actualArtworkUrl = null
            lastProgressMs = 0L
            lastPercentComplete = 0f
            consecutivePollFailures = 0
            lastPositionChangeTime = System.currentTimeMillis()
            startStatePolling()
            Log.d(TAG, "play() total flow took ${System.currentTimeMillis() - flowStart}ms")
        } else {
            Log.e(TAG, "Failed to play '${track.title}' (${System.currentTimeMillis() - flowStart}ms)")
        }
    }

    // ── Unified play flow (replaces old playWithRetry + attemptPlay) ────

    /**
     * Single-pass play flow with no compounding retry layers.
     *
     * 1. Ensure a device is available (wake Spotify if needed)
     * 2. Pick the best device (auto-select or show picker)
     * 3. Start playback with device_id
     * 4. On 502: single retry after 1s
     * 5. On cold device: quick verification (2 polls × 500ms)
     *
     * Old flow had 3 layers of retries (playWithRetry × 502 retries × verification)
     * compounding to 27.5s worst case. This flow is single-pass: ~2-5s cold, ~200ms warm.
     */
    private suspend fun attemptPlay(auth: String, uri: String, flowStart: Long): Boolean {

        // ── Phase 7: Optimistic warm-path ────────────────────────────
        // When device is already verified and we have a preferred device,
        // fire startPlayback immediately without any device fetch.
        val preferredId = settingsStore.getPreferredSpotifyDeviceId()
        if (deviceVerified && preferredId != null && preferredId != LOCAL_DEVICE_ID) {
            Log.d(TAG, "Warm path: firing startPlayback directly on preferred=$preferredId")
            try {
                val resp = spotifyApi.startPlayback(auth, SpPlaybackRequest(uris = listOf(uri)), deviceId = preferredId)
                if (resp.isSuccessful || resp.code() == 204) {
                    Log.d(TAG, "Warm path succeeded in ${System.currentTimeMillis() - flowStart}ms")
                    return true
                }
                Log.d(TAG, "Warm path failed (${resp.code()}), falling through to full flow")
            } catch (e: Exception) {
                Log.d(TAG, "Warm path error: ${e.message}, falling through to full flow")
            }
            // Fall through: device may have gone offline, do full flow
        }

        // ── Phase 1+3: Ensure device ─────────────────────────────────
        val ensureStart = System.currentTimeMillis()
        val devices = ensureDevice(auth)
        Log.d(TAG, "ensureDevice took ${System.currentTimeMillis() - ensureStart}ms, found ${devices.size} device(s)")
        Log.d(TAG, "Devices: ${devices.map { "${it.name}(active=${it.isActive}, type=${it.type})" }}")

        // ── Phase 5: Pick device ─────────────────────────────────────
        val pickStart = System.currentTimeMillis()
        val pickedDevice = pickDevice(devices)
        Log.d(TAG, "pickDevice took ${System.currentTimeMillis() - pickStart}ms")
        if (pickedDevice == null) {
            Log.w(TAG, "No device selected (user cancelled or no devices)")
            return false
        }

        // Resolve synthetic "This device" to a real device ID
        val targetDevice = if (isLocalPlaceholder(pickedDevice)) {
            resolveLocalDevice(auth)
        } else {
            pickedDevice
        }
        if (targetDevice == null) {
            _playbackError.value = "Couldn't find Spotify on this phone. Open Spotify and try again."
            return false
        }
        Log.d(TAG, "Target: '${targetDevice.name}' (type=${targetDevice.type}, active=${targetDevice.isActive})")

        // ── Phase 4: Transfer (no hardcoded delay) ───────────────────
        if (!targetDevice.isActive) {
            Log.d(TAG, "Transferring playback to '${targetDevice.name}'")
            try {
                val transferResp = spotifyApi.transferPlayback(auth, SpTransferRequest(deviceIds = listOf(targetDevice.id)))
                if (!transferResp.isSuccessful) {
                    Log.w(TAG, "Transfer returned ${transferResp.code()}: ${transferResp.errorBody()?.string()}")
                } else {
                    Log.d(TAG, "Transfer accepted (${transferResp.code()})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed (non-fatal): ${e.message}")
            }
            // No hardcoded delay — device_id on startPlayback routes correctly.
            // On 502 (device not ready), we retry once below.
        }

        // ── Phase 2: Start playback (single retry on 502) ───────────
        Log.d(TAG, "startPlayback: uri=$uri, deviceId=${targetDevice.id}")
        val playStart = System.currentTimeMillis()
        for (attempt in 1..2) {
            try {
                val resp = spotifyApi.startPlayback(auth, SpPlaybackRequest(uris = listOf(uri)), deviceId = targetDevice.id)

                if (resp.isSuccessful || resp.code() == 204) {
                    Log.d(TAG, "Playback accepted on '${targetDevice.name}' (attempt $attempt, ${System.currentTimeMillis() - playStart}ms)")

                    // Quick verification on cold devices only
                    if (!deviceVerified) {
                        val verified = verifyPlaybackStarted(auth)
                        deviceVerified = true
                        if (!verified) {
                            Log.w(TAG, "Playback not verified but proceeding optimistically")
                        }
                    }
                    return true
                }

                val code = resp.code()
                val body = resp.errorBody()?.string()
                Log.w(TAG, "startPlayback failed: $code body=$body (attempt $attempt)")

                when {
                    code == 502 && attempt == 1 -> {
                        Log.d(TAG, "Device not ready (502), retrying in 1s...")
                        delay(1000)
                    }
                    code == 403 -> {
                        _playbackError.value = "Spotify Premium is required for playback"
                        return false
                    }
                    code == 404 -> {
                        // Device gone — clear preference so next play re-discovers
                        settingsStore.clearPreferredSpotifyDeviceId()
                        deviceVerified = false
                        _playbackError.value = "Spotify device not responding. Try again."
                        return false
                    }
                    else -> {
                        _playbackError.value = "Spotify playback failed ($code). Try again."
                        settingsStore.clearPreferredSpotifyDeviceId()
                        return false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startPlayback error (attempt $attempt): ${e.message}")
                if (attempt == 2) {
                    _playbackError.value = "Couldn't reach Spotify. Check your connection."
                    return false
                }
                delay(1000)
            }
        }
        return false
    }

    /**
     * Quick verification that playback actually started on a cold device.
     * 2 polls at 500ms intervals (1s total) — much faster than the old
     * 5 polls at 1s intervals (5s).
     */
    private suspend fun verifyPlaybackStarted(auth: String): Boolean {
        for (poll in 1..2) {
            delay(500)
            try {
                val state = spotifyApi.getPlaybackState(auth)
                val body = if (state.isSuccessful) state.body() else null
                if (body != null && body.isPlaying) {
                    Log.d(TAG, "verifyPlayback: confirmed playing after ${poll * 500}ms")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "verifyPlayback poll $poll failed: ${e.message}")
            }
        }
        return false
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
        deviceVerified = false
    }

    // ── Unified device resolution (replaces old wake + local wake) ─────

    /**
     * Ensure at least one Spotify Connect device is available.
     * Wakes Spotify on this phone if no devices are found.
     *
     * Replaces the old split of wakeSpotifyAndAwaitDevice() + wakeLocalSpotifyApp()
     * which had separate 15s polling loops. Now a single 8s flow with 300ms polling.
     */
    private suspend fun ensureDevice(auth: String): List<SpDevice> {
        // Fast path: if device is already verified (warm), just fetch devices
        if (deviceVerified) {
            return try {
                spotifyApi.getDevices(auth).devices
            } catch (e: Exception) {
                Log.w(TAG, "Device fetch failed: ${e.message}")
                emptyList()
            }
        }

        // Check for existing devices without waking (Spotify already running)
        try {
            val devices = spotifyApi.getDevices(auth).devices
            if (devices.isNotEmpty()) {
                Log.d(TAG, "Devices already available (no wake needed): ${devices.size}")
                deviceVerified = true
                return devices
            }
        } catch (e: Exception) {
            Log.d(TAG, "Initial device fetch failed: ${e.message}")
        }

        // No devices — nudge Spotify awake via broadcast (invisible) and poll.
        // If the broadcast doesn't work after 4s, fall back to launch intent.
        ensureSpotifyRunning()
        Log.d(TAG, "Polling for devices (${WAKE_POLL_INTERVAL_MS}ms intervals, ${WAKE_TIMEOUT_MS}ms timeout)...")

        delay(WAKE_INITIAL_DELAY_MS) // Give Spotify time to initialize
        var deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
        val fallbackTime = System.currentTimeMillis() + 2000
        var pauseSent = false
        var pollCount = 0
        var fallbackFired = false
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            try {
                val devices = spotifyApi.getDevices(auth).devices
                if (devices.isNotEmpty()) {
                    Log.d(TAG, "Spotify woke up after $pollCount polls — found ${devices.size} device(s)")
                    // Silence any stale playback from the wake
                    if (!pauseSent) {
                        pauseSent = true
                        try { spotifyApi.pausePlayback(auth) } catch (_: Exception) {}
                    }
                    deviceVerified = true
                    return devices
                }
            } catch (e: Exception) {
                if (pollCount % 10 == 0) Log.d(TAG, "Device poll #$pollCount failed: ${e.message}")
            }
            if (!fallbackFired && System.currentTimeMillis() > fallbackTime) {
                fallbackFired = true
                Log.d(TAG, "Broadcast didn't wake Spotify after 2s, trying launch intent fallback")
                launchSpotifyFallback()
                deadline = System.currentTimeMillis() + 12_000
            }
            delay(WAKE_POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Timed out waiting for Spotify device after $pollCount polls (${WAKE_TIMEOUT_MS}ms)")
        _playbackError.value = "Couldn't find a Spotify device. Open Spotify and try again."
        return emptyList()
    }

    /**
     * Resolve the synthetic "This device" entry to a real Spotify Connect device ID.
     *
     * Always launches Spotify — even when remote devices exist (deviceVerified=true
     * from finding remote devices like TVs/computers), the LOCAL Spotify app may
     * not be running. We need Spotify running on this phone to register as a
     * Smartphone Connect device.
     */
    private suspend fun resolveLocalDevice(auth: String): SpDevice? {
        Log.d(TAG, "Resolving local device: model='${Build.MODEL}', manufacturer='${Build.MANUFACTURER}'")
        val localModel = Build.MODEL.lowercase()
        val localManufacturer = Build.MANUFACTURER.lowercase()

        // Always ensure Spotify is running on this phone. Remote devices
        // (TVs, computers) can appear in the API without local Spotify running.
        // Use broadcast first (invisible), fall back to launch intent after 4s.
        ensureSpotifyRunning()
        delay(WAKE_INITIAL_DELAY_MS)

        var deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
        val fallbackTime = System.currentTimeMillis() + 2000
        var pollCount = 0
        var fallbackFired = false
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            try {
                val devices = spotifyApi.getDevices(auth).devices
                val local = devices.firstOrNull { d ->
                    d.type == "Smartphone" && (
                        d.name.lowercase().contains(localModel) ||
                        d.name.lowercase().contains(localManufacturer)
                    )
                } ?: devices.firstOrNull { it.type == "Smartphone" }

                if (local != null) {
                    deviceVerified = true
                    // Keep LOCAL_DEVICE_ID as the preference — the real device ID
                    // changes between Spotify sessions (process restarts). Saving
                    // the real ID causes "preferred device not found" on every cold
                    // start, forcing the picker to show repeatedly.
                    Log.d(TAG, "Resolved local device after $pollCount polls: '${local.name}' id=${local.id}")
                    try { spotifyApi.pausePlayback(auth) } catch (_: Exception) {}
                    return local
                }
                if (pollCount % 10 == 0) {
                    Log.d(TAG, "resolveLocal poll #$pollCount: ${devices.map { "'${it.name}'(type=${it.type})" }}")
                }
            } catch (_: Exception) {}
            // Fallback to launch intent if broadcast didn't wake Spotify
            if (!fallbackFired && System.currentTimeMillis() > fallbackTime) {
                fallbackFired = true
                Log.d(TAG, "Broadcast didn't wake local Spotify after 2s, trying launch intent")
                launchSpotifyFallback()
                // Extend deadline — cold-launching Spotify needs ~10-15s to register
                // as a Connect device (process start + auth + server registration)
                deadline = System.currentTimeMillis() + 12_000
                Log.d(TAG, "Extended timeout to 12s for cold launch")
            }
            delay(WAKE_POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Could not find local Smartphone device after $pollCount polls")
        return null
    }

    /**
     * Wake Spotify's process without showing its UI.
     *
     * Primary: targeted KEYCODE_MEDIA_PLAY broadcast. This wakes Spotify's
     * MediaBrowserService in the background. Targeted broadcasts (with
     * setPackage) work on all Android versions including 12+; only implicit
     * broadcasts are restricted.
     *
     * The launch intent is NOT used here — it brings Spotify's activity to
     * the foreground which is jarring ("one music app opening another").
     * It's only used as a last-resort fallback from [ensureDevice] if the
     * broadcast doesn't wake Spotify within half the timeout.
     */
    private fun ensureSpotifyRunning() {
        try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(SPOTIFY_PACKAGE)
                putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
            }
            context.sendBroadcast(downIntent)

            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(SPOTIFY_PACKAGE)
                putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
            }
            context.sendBroadcast(upIntent)
            Log.d(TAG, "Nudged Spotify awake via media button broadcast")
        } catch (e: Exception) {
            Log.w(TAG, "Media button broadcast failed: ${e.message}")
        }
    }

    /**
     * Last-resort Spotify wake: launch the activity, then immediately bring
     * our app back to the foreground so the user barely sees Spotify's UI.
     * Only used when the media button broadcast failed (Spotify fully killed).
     */
    private fun launchSpotifyFallback() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            try {
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched Spotify via activity intent (fallback)")
                // Note: we intentionally do NOT bring our app back to front here.
                // Doing so causes Android to deprioritize Spotify before it can
                // finish initializing and register as a Connect device. The user
                // will briefly see Spotify, but playback will work. Our polling
                // loop will continue in the background.
            } catch (e: Exception) {
                Log.w(TAG, "Launch intent failed: ${e.message}")
            }
        } else {
            _playbackError.value = "Spotify is not installed"
        }
    }

    /**
     * Build a synthetic "This device" entry so the local phone always appears
     * in the picker — even when Spotify hasn't registered it yet.
     */
    private fun localDeviceEntry(): SpDevice = SpDevice(
        id = LOCAL_DEVICE_ID,
        name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
        isActive = false,
        isRestricted = false,
        type = "Smartphone",
    )

    /**
     * True when [device] is the synthetic "This device" placeholder, meaning
     * we need to wake Spotify locally and resolve a real device ID.
     */
    private fun isLocalPlaceholder(device: SpDevice): Boolean =
        device.id == LOCAL_DEVICE_ID

    /**
     * Pick the best device to play on.
     *
     * Improvements over old picker:
     * - Accepts inactive preferred devices (transfer will activate them)
     *   instead of showing the picker on every cold start
     * - Auto-selects when 1 real device + synthetic entry (don't show picker)
     * - Filters restricted devices from the picker
     */
    private suspend fun pickDevice(devices: List<SpDevice>): SpDevice? {
        val controllable = devices.filter { !it.isRestricted }
        val available = controllable.ifEmpty { devices }

        // Inject synthetic "This device" if no local smartphone found
        val localModel = Build.MODEL.lowercase()
        val localManufacturer = Build.MANUFACTURER.lowercase()
        val hasLocalDevice = available.any { device ->
            if (device.type != "Smartphone") return@any false
            val name = device.name.lowercase()
            name.contains(localModel) || name.contains(localManufacturer)
        } || available.count { it.type == "Smartphone" } == 1
        val withLocal = if (hasLocalDevice) available else available + localDeviceEntry()

        Log.d(TAG, "pickDevice: ${withLocal.map { "'${it.name}'(type=${it.type}, active=${it.isActive})" }}")

        val preferredId = settingsStore.getPreferredSpotifyDeviceId()

        // 1. Preferred = local placeholder → honour it
        if (preferredId == LOCAL_DEVICE_ID) {
            Log.d(TAG, "Preferred device is local — returning local placeholder")
            return withLocal.firstOrNull { isLocalPlaceholder(it) } ?: localDeviceEntry()
        }

        // 2. Preferred device found → use it (even if inactive — transfer will activate)
        //    Only clear preference when the device is completely absent from the list.
        if (preferredId != null) {
            val preferred = withLocal.firstOrNull { it.id == preferredId }
            if (preferred != null) {
                Log.d(TAG, "Using preferred device: '${preferred.name}' (active=${preferred.isActive})")
                return preferred
            }
            // Device gone — clear preference so picker shows
            Log.d(TAG, "Preferred device $preferredId no longer available, clearing")
            settingsStore.clearPreferredSpotifyDeviceId()
        }

        // 3. Single real device (+ optional synthetic entry) → auto-select
        val realDevices = withLocal.filter { !isLocalPlaceholder(it) }
        if (realDevices.size == 1) {
            Log.d(TAG, "Single real device, auto-selecting '${realDevices[0].name}'")
            return realDevices[0]
        }
        if (withLocal.size == 1) {
            Log.d(TAG, "Only local placeholder available, auto-selecting")
            return withLocal[0]
        }

        // 4. Already-active device → use it
        withLocal.firstOrNull { it.isActive }?.let { active ->
            Log.d(TAG, "Using already-active device '${active.name}'")
            return active
        }

        // 5. Multiple devices — show picker
        Log.d(TAG, "Multiple devices, showing picker dialog")
        _devicePickerRequest.value?.deferred?.complete(null) // Cancel stale picker
        val deferred = CompletableDeferred<SpDevice?>()
        _devicePickerRequest.value = DevicePickerRequest(withLocal, deferred)

        val chosen = deferred.await()
        if (chosen != null) {
            settingsStore.setPreferredSpotifyDeviceId(chosen.id)
            Log.d(TAG, "User picked '${chosen.name}', saved as preferred")
        }
        return chosen
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

        // Grace period: don't trigger auto-advance within 5s of starting playback.
        // Spotify's API can return stale/empty state for a few seconds after play(),
        // especially after device transfers. This prevents false positives from
        // scenarios #2-#5 firing on transient API state.
        val elapsed = System.currentTimeMillis() - playStartedAt
        if (elapsed < 5000) return false

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

        // 6. Poll failures watchdog: if we can't reach the API for ~10s,
        // the track likely ended and Spotify went idle.
        // IMPORTANT: Only trigger this when we have NO recent successful polls.
        // During Doze mode, network requests can fail transiently — the track
        // may still be playing fine on the Spotify side. Require failures to
        // significantly exceed the threshold before treating as done.
        if (consecutivePollFailures >= MAX_POLL_FAILURES) {
            Log.w(TAG, "Watchdog: $consecutivePollFailures consecutive poll failures, treating as done")
            return true
        }

        // 7. Stale-position watchdog: if position hasn't changed for 15+ seconds
        // while supposedly playing, the track is stuck or ended silently.
        // Uses wall-clock time instead of poll count — the controller polls at
        // 500ms but the handler updates cached state at ~1s + API latency, so
        // count-based detection false-positives when the API is slow.
        // CRITICAL: Do NOT fire during poll failures — if we can't reach the
        // API, position won't update, but that doesn't mean the track stopped.
        // The stale-position watchdog should only trigger when polls ARE
        // succeeding but position isn't advancing (actual playback stall).
        if (cachedIsPlaying && cachedPosition > 0 && lastPositionChangeTime > 0 &&
            consecutivePollFailures == 0
        ) {
            val staleDuration = System.currentTimeMillis() - lastPositionChangeTime
            if (staleDuration >= 15000) {
                Log.w(TAG, "Watchdog: position stale at ${cachedPosition}ms for ${staleDuration}ms (polls OK), treating as done")
                return true
            }
        } else if (cachedIsPlaying && consecutivePollFailures > 0 && lastPositionChangeTime > 0) {
            val staleDuration = System.currentTimeMillis() - lastPositionChangeTime
            if (staleDuration >= 15000) {
                Log.w(TAG, "Watchdog: position stale ${staleDuration}ms BUT $consecutivePollFailures poll failures — NOT treating as done (likely Doze)")
            }
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
                        consecutivePollFailures = 0
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
                        } else if (!playbackConfirmed && !state.isPlaying && elapsed in 3000..10000 && !pausedByUs) {
                            // Spotify accepted our play command but didn't start.
                            // Re-send the play command once as a lightweight retry.
                            val uri = lastPlayedUri
                            Log.w(TAG, "Playback not started ${elapsed}ms after play — retrying startPlayback")
                            if (uri != null) {
                                try {
                                    withAuth { auth ->
                                        spotifyApi.startPlayback(auth, SpPlaybackRequest(uris = listOf(uri)))
                                    }
                                    playStartedAt = System.currentTimeMillis()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Retry startPlayback failed: ${e.message}")
                                }
                            }
                            playbackConfirmed = true // Don't retry again
                        } else {
                            val prevPosition = cachedPosition
                            cachedIsPlaying = state.isPlaying
                            cachedPosition = state.progressMs ?: 0L
                            currentItemId = apiItemId

                            // Mark playback confirmed once Spotify reports playing
                            if (state.isPlaying && !playbackConfirmed) {
                                playbackConfirmed = true
                            }

                            // Track when position last changed (for stale detection)
                            if (cachedPosition != prevPosition) {
                                lastPositionChangeTime = System.currentTimeMillis()
                            }

                            // Handle Spotify track relinking: when Spotify substitutes
                            // a different version of the same track (different market,
                            // album edition, etc.), the track ID changes. Lock in the
                            // actual ID once Spotify confirms it's playing, so scenario
                            // #4 (track-changed) doesn't false-positive.
                            if (state.isPlaying && apiItemId != null && apiItemId != expectedTrackId && elapsed < 15000) {
                                Log.d(TAG, "Track relinked: expected=$expectedTrackId, actual=$apiItemId — locking in actual ID")
                                expectedTrackId = apiItemId
                            }

                            // Preserve duration when item becomes null (track just ended)
                            if (state.item?.durationMs != null) {
                                cachedDuration = state.item.durationMs
                            }

                            // Capture actual track metadata from Spotify
                            state.item?.let { item ->
                                actualTitle = item.name
                                actualArtist = item.artistName
                                actualAlbum = item.album?.name
                                actualArtworkUrl = item.album?.images
                                    ?.filter { it.url != null }
                                    ?.sortedByDescending { it.width ?: 0 }
                                    ?.firstOrNull()?.url
                            }
                        }
                    } else {
                        consecutivePollFailures++
                        if (consecutivePollFailures == 1 || consecutivePollFailures % 5 == 0) {
                            Log.w(TAG, "State poll returned null ($consecutivePollFailures consecutive, lastPos=$cachedPosition, playing=$cachedIsPlaying)")
                        }
                    }
                } catch (e: Exception) {
                    consecutivePollFailures++
                    Log.w(TAG, "State poll failed ($consecutivePollFailures): ${e.message}")
                }
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
        val token = settingsStore.getSpotifyAccessToken()
        if (token == null) {
            Log.w(TAG, "withAuth: no Spotify access token stored — skipping API call")
            return null
        }
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
