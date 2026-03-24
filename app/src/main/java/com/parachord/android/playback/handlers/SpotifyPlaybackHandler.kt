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
        private const val MAX_POLL_FAILURES = 10
        /** ~8 seconds of stale position (8 polls at 1s interval). */
        private const val MAX_STALE_POSITION = 8
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        /** Max time to wait for Spotify to register as a Connect device after launching. */
        private const val WAKE_TIMEOUT_MS = 15000L
        /** Interval between device-list polls while waiting for Spotify to wake. */
        private const val WAKE_POLL_INTERVAL_MS = 1000L
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
    /** True after we've successfully woken Spotify and found devices this session. */
    private var hasWokenSpotify = false

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
    /** True when WE initiated a pause (user tapped pause). False means Spotify stopped on its own. */
    private var pausedByUs = false

    // Track previous poll state for reset-after-end detection (matches desktop pattern)
    private var lastProgressMs = 0L
    private var lastPercentComplete = 0f

    /** Consecutive state-polling failures — used for watchdog auto-advance. */
    private var consecutivePollFailures = 0
    /** Consecutive polls where position didn't change while "playing". */
    private var stalePositionCount = 0

    /** True when the connection to Spotify is experiencing sustained issues (not transient blips). */
    val isConnectionStalled: Boolean
        get() = consecutivePollFailures >= 3 || stalePositionCount >= 4

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
            // Clear actual metadata until polling confirms what's really playing
            actualTitle = null
            actualArtist = null
            actualAlbum = null
            actualArtworkUrl = null
            lastProgressMs = 0L
            lastPercentComplete = 0f
            consecutivePollFailures = 0
            stalePositionCount = 0
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

                if (result == null) {
                    Log.w(TAG, "Play attempt $attempt: withAuth returned null (no token or auth error)")
                }

                if (attempt < 3) {
                    Log.d(TAG, "Play attempt $attempt failed (result=$result), retrying in ${attempt}s...")
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
     * Device selection (mirrors desktop device picker):
     * 1. Check preferred device — use it if available
     * 2. Single device — auto-select (no UX regression)
     * 3. Multiple devices — show picker dialog, save choice as preferred
     * 4. Preferred device unavailable — show picker again
     */
    private suspend fun attemptPlay(auth: String, uri: String): Boolean {
        // On the first play of a session, wake the local Spotify app so this
        // phone registers as a Connect device. Subsequent tracks skip the wake
        // since Spotify is already running.
        val devices = if (!hasWokenSpotify) {
            val result = wakeSpotifyAndAwaitDevice(auth)
            if (result.isNotEmpty()) hasWokenSpotify = true
            result
        } else {
            spotifyApi.getDevices(auth).devices
        }
        Log.d(TAG, "Devices: ${devices.map { "${it.name}(active=${it.isActive}, type=${it.type})" }}")

        // Even with zero API devices, pickDevice() injects "This device" so
        // the user can always force-wake Spotify on this phone.
        val pickedDevice = pickDevice(devices)
        if (pickedDevice == null) {
            Log.w(TAG, "No suitable device found (user cancelled or no devices)")
            return false
        }

        // If the user picked the synthetic "This device" entry, wake Spotify
        // locally and resolve the real Connect device ID for this phone.
        val targetDevice = if (isLocalPlaceholder(pickedDevice)) {
            Log.d(TAG, "Local device selected — waking Spotify to get real device ID")
            // Force-wake the local Spotify app — don't use the fast path that
            // short-circuits on already-available remote devices (e.g. "bedroom TV").
            val localDevices = wakeLocalSpotifyApp(auth)
            val localModel = Build.MODEL.lowercase()
            Log.d(TAG, "Resolving local device: model='$localModel', candidates=${localDevices.map { "'${it.name}'(type=${it.type}, id=${it.id})" }}")
            val resolved = localDevices.firstOrNull {
                it.type == "Smartphone" && it.name.lowercase().contains(localModel)
            } ?: localDevices.firstOrNull { it.type == "Smartphone" }
            if (resolved == null) {
                Log.w(TAG, "Could not find local Smartphone device after waking Spotify. All devices: ${localDevices.map { "'${it.name}'(type=${it.type})" }}")
                // Keep the LOCAL_DEVICE_ID preference — the next retry will go
                // straight back to wakeLocalSpotifyApp() without re-showing the
                // picker. The prior nudge is likely still waking Spotify, so the
                // retry has a good chance of finding the device.
                return false
            }
            hasWokenSpotify = true
            // Update the preferred device to the real ID so we skip the picker next time
            settingsStore.setPreferredSpotifyDeviceId(resolved.id)
            Log.d(TAG, "Resolved local device: '${resolved.name}' id=${resolved.id}")
            resolved
        } else {
            pickedDevice
        }

        Log.d(TAG, "Target device: '${targetDevice.name}' (type=${targetDevice.type}, active=${targetDevice.isActive})")

        // Transfer playback to the target device if it's not already active
        if (!targetDevice.isActive) {
            Log.d(TAG, "Transferring playback to '${targetDevice.name}' id=${targetDevice.id}")
            try {
                val transferResp = spotifyApi.transferPlayback(auth, SpTransferRequest(deviceIds = listOf(targetDevice.id)))
                if (!transferResp.isSuccessful) {
                    val body = transferResp.errorBody()?.string()
                    Log.w(TAG, "Transfer returned ${transferResp.code()}: $body")
                } else {
                    Log.d(TAG, "Transfer accepted (${transferResp.code()})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed (non-fatal): ${e.message}")
            }
            // Give Spotify time to activate the device. 2s is safer than 1s — the
            // device just woke up and needs time to register with Spotify's servers.
            delay(2000)
        }

        // Force device_id in the play call (desktop pattern) — ensures playback
        // goes to the right device even if transfer hasn't fully settled.
        // Retry on 502 (device not ready) with increasing delays — Spotify returns
        // 502 when the device hasn't fully registered after a transfer/wake.
        for (playRetry in 1..3) {
            Log.d(TAG, "Calling startPlayback (try $playRetry): uri=$uri, deviceId=${targetDevice.id}")
            val response = spotifyApi.startPlayback(
                auth,
                SpPlaybackRequest(uris = listOf(uri)),
                deviceId = targetDevice.id,
            )

            if (response.isSuccessful || response.code() == 204) {
                Log.d(TAG, "Playback started on '${targetDevice.name}' for $uri")
                return true
            }

            val errorBody = response.errorBody()?.string()
            Log.w(TAG, "Start playback failed on '${targetDevice.name}': ${response.code()} body=$errorBody")

            // 502 = device not ready yet. Retry with increasing delay.
            if (response.code() == 502 && playRetry < 3) {
                val waitMs = playRetry * 2000L
                Log.d(TAG, "Device not ready (502), waiting ${waitMs}ms before retry...")
                delay(waitMs)
                continue
            }

            // Non-502 failure or final 502 — give up
            settingsStore.clearPreferredSpotifyDeviceId()
            return false
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
        hasWokenSpotify = false
    }

    /**
     * Find available Spotify Connect devices, waking the local Spotify app
     * only as a last resort.
     *
     * 1. Check for existing devices via the Web API (no app launch).
     * 2. If none found, send a background intent to nudge Spotify awake
     *    without stealing focus, then poll for devices.
     */
    private suspend fun wakeSpotifyAndAwaitDevice(auth: String): List<SpDevice> {
        // Fast path: check for devices already available (Spotify running,
        // or devices on other machines/phones on the account)
        try {
            val devices = spotifyApi.getDevices(auth).devices
            if (devices.isNotEmpty()) {
                Log.d(TAG, "Devices already available (no wake needed): ${devices.size}")
                return devices
            }
        } catch (e: Exception) {
            Log.d(TAG, "Initial device fetch failed: ${e.message}")
        }

        // No devices — nudge Spotify awake via a media button broadcast.
        // This wakes Spotify's MediaBrowserService in the background without
        // launching the full UI or stealing focus from our app.
        nudgeSpotifyBackground()

        // Poll until Spotify registers as a Connect device
        Log.d(TAG, "Polling for devices for up to ${WAKE_TIMEOUT_MS}ms...")
        val deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(WAKE_POLL_INTERVAL_MS)
            try {
                val devices = spotifyApi.getDevices(auth).devices
                if (devices.isNotEmpty()) {
                    Log.d(TAG, "Spotify woke up — found ${devices.size} device(s)")
                    return devices
                }
            } catch (e: Exception) {
                Log.d(TAG, "Device poll during wake failed: ${e.message}")
            }
        }

        Log.w(TAG, "Timed out waiting for Spotify device after ${WAKE_TIMEOUT_MS}ms")
        return emptyList()
    }

    /**
     * Force-wake Spotify on this phone and wait for it to register as a
     * Connect device. Unlike [wakeSpotifyAndAwaitDevice], this always nudges
     * Spotify — even when remote devices (speakers, TVs) are already
     * available — because the user explicitly asked for "This device".
     */
    private suspend fun wakeLocalSpotifyApp(auth: String): List<SpDevice> {
        nudgeSpotifyBackground()

        // Poll until we see a Smartphone device (the local phone)
        val localModel = Build.MODEL.lowercase()
        Log.d(TAG, "wakeLocalSpotifyApp: looking for Smartphone matching model='$localModel' (Build.MODEL=${Build.MODEL}, Build.MANUFACTURER=${Build.MANUFACTURER})")
        val deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            delay(WAKE_POLL_INTERVAL_MS)
            pollCount++
            try {
                val devices = spotifyApi.getDevices(auth).devices
                Log.d(TAG, "wakeLocal poll #$pollCount: ${devices.map { "'${it.name}'(type=${it.type}, id=${it.id}, active=${it.isActive})" }}")
                val hasLocal = devices.any {
                    it.type == "Smartphone" && it.name.lowercase().contains(localModel)
                } || devices.any { it.type == "Smartphone" }
                if (hasLocal) {
                    Log.d(TAG, "Local Spotify device found after wake: ${devices.size} device(s)")
                    return devices
                }
            } catch (e: Exception) {
                Log.d(TAG, "Device poll during local wake failed: ${e.message}")
            }
        }

        // Last resort — return whatever devices exist even if no smartphone found
        Log.w(TAG, "wakeLocalSpotifyApp: timed out after ${pollCount} polls, no Smartphone found for model='$localModel'")
        return try {
            val devices = spotifyApi.getDevices(auth).devices
            Log.d(TAG, "wakeLocal last-resort devices: ${devices.map { "'${it.name}'(type=${it.type}, id=${it.id})" }}")
            devices
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Silently nudge Spotify awake via a media button broadcast.
     * This wakes Spotify's MediaBrowserService process without launching
     * its activity or stealing focus from our app.
     */
    private fun nudgeSpotifyBackground() {
        try {
            // Send a PLAY media button event targeted at Spotify's package.
            // This wakes the Spotify process and registers it as a Connect
            // device, without opening the Spotify UI.
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
            Log.w(TAG, "Media button broadcast failed, falling back to launch intent")
            // Fallback: launch activity if broadcast doesn't work (older Android)
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                context.startActivity(launchIntent)
            }
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
     * Pick the best device to play on (matches desktop device picker behavior).
     *
     * 1. Preferred device (saved from previous picker selection) — use if available
     * 2. Single device — auto-select without prompting
     * 3. Multiple devices — show picker dialog, save selection as preferred
     * 4. Preferred device not found — show picker again
     *
     * Always injects a synthetic "This device" entry so the local phone is
     * selectable even when Spotify hasn't registered it as a Connect device yet.
     */
    private suspend fun pickDevice(devices: List<SpDevice>): SpDevice? {
        val controllable = devices.filter { !it.isRestricted }
        val available = controllable.ifEmpty { devices }
        Log.d(TAG, "pickDevice: ${devices.size} total, ${controllable.size} controllable, ${devices.size - controllable.size} restricted")

        // Always ensure the local device appears in the list. If Spotify
        // hasn't registered this phone yet, inject a synthetic entry so the
        // user can pick "This device" and we'll wake Spotify on demand.
        // Check both model name and manufacturer — Spotify device names vary
        // (users can rename them, and the API may return "Phone", the model
        // number, or a custom name). A Smartphone-type device is enough to
        // confirm the local phone is already registered.
        val localModel = Build.MODEL.lowercase()
        val localManufacturer = Build.MANUFACTURER.lowercase()
        val hasLocalDevice = available.any { device ->
            if (device.type != "Smartphone") return@any false
            val name = device.name.lowercase()
            // Match by model name, manufacturer, or if there's only one smartphone
            name.contains(localModel) || name.contains(localManufacturer)
        } || available.count { it.type == "Smartphone" } == 1
        val withLocal = if (hasLocalDevice) available else available + localDeviceEntry()

        Log.d(TAG, "pickDevice: devices=${withLocal.map { "'${it.name}'(type=${it.type}, active=${it.isActive})" }}")

        // 1. Check preferred device — only auto-select if it's currently active.
        // Inactive preferred devices (phantom speakers, TVs on other networks)
        // should not be auto-targeted; show the picker so the user can choose.
        val preferredId = settingsStore.getPreferredSpotifyDeviceId()
        if (preferredId == LOCAL_DEVICE_ID) {
            // User previously chose "This device" — honour that choice and return
            // the synthetic local entry so attemptPlay() can wake Spotify locally.
            Log.d(TAG, "Preferred device is local — returning local placeholder")
            return withLocal.firstOrNull { isLocalPlaceholder(it) } ?: localDeviceEntry()
        }
        if (preferredId != null) {
            val preferred = withLocal.firstOrNull { it.id == preferredId }
            if (preferred != null && preferred.isActive) {
                Log.d(TAG, "Using preferred device (active): '${preferred.name}'")
                return preferred
            }
            if (preferred != null) {
                Log.d(TAG, "Preferred device '${preferred.name}' found but not active, clearing preference")
            } else {
                Log.d(TAG, "Preferred device $preferredId not available, clearing preference")
            }
            // Clear stale preference so the picker shows and user can pick fresh
            settingsStore.clearPreferredSpotifyDeviceId()
        }

        // 2. Single device — auto-select (no UX regression for simple setups)
        if (withLocal.size == 1) {
            Log.d(TAG, "Single device, auto-selecting '${withLocal[0].name}'")
            return withLocal[0]
        }

        // 3. Already-active device with no preferred set — use it
        if (preferredId == null) {
            withLocal.firstOrNull { it.isActive }?.let { active ->
                Log.d(TAG, "No preferred set, using already-active device '${active.name}'")
                return active
            }
        }

        // 4. Multiple devices — show picker dialog and wait for user choice
        Log.d(TAG, "Multiple devices available, showing picker dialog")
        // Cancel any in-flight picker from a concurrent play call
        _devicePickerRequest.value?.deferred?.complete(null)
        val deferred = CompletableDeferred<SpDevice?>()
        _devicePickerRequest.value = DevicePickerRequest(withLocal, deferred)

        val chosen = deferred.await()
        if (chosen != null) {
            // Save as preferred device (desktop: preferred_spotify_device_id)
            settingsStore.setPreferredSpotifyDeviceId(chosen.id)
            Log.d(TAG, "User picked device '${chosen.name}', saved as preferred")
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
        if (consecutivePollFailures >= MAX_POLL_FAILURES) {
            Log.w(TAG, "Watchdog: $consecutivePollFailures consecutive poll failures, treating as done")
            return true
        }

        // 7. Stale-position watchdog: if position hasn't changed across multiple
        // polls while supposedly playing, the track is stuck or ended silently.
        if (cachedIsPlaying && cachedPosition > 0 && cachedPosition == lastProgressMs) {
            stalePositionCount++
            if (stalePositionCount >= MAX_STALE_POSITION) {
                Log.w(TAG, "Watchdog: position stale at ${cachedPosition}ms for $stalePositionCount polls, treating as done")
                return true
            }
        } else {
            stalePositionCount = 0
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
                        } else {
                            cachedIsPlaying = state.isPlaying
                            cachedPosition = state.progressMs ?: 0L
                            currentItemId = apiItemId

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
