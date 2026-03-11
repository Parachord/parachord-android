package com.parachord.android.playback.handlers

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.db.entity.TrackEntity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles Spotify playback through the Spotify App Remote SDK.
 *
 * This mirrors the desktop app's Spotify Connect approach but uses the Android-native
 * App Remote SDK instead, which controls the Spotify app running on the same device.
 * Requires the Spotify app to be installed.
 */
@Singleton
class SpotifyPlaybackHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "SpotifyPlayback"
    }

    private var appRemote: SpotifyAppRemote? = null

    private val _playerState = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = _playerState.asStateFlow()

    override val isConnected: Boolean
        get() = appRemote?.isConnected == true

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "spotify" && track.spotifyUri != null

    override suspend fun createMediaItem(track: TrackEntity): Nothing? {
        // Spotify playback is external — not through ExoPlayer
        return null
    }

    /** Connect to Spotify App Remote. Must be called before playback. */
    suspend fun connect(): Boolean {
        if (appRemote?.isConnected == true) return true

        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        if (clientId.isBlank()) {
            Log.e(TAG, "No Spotify client ID configured")
            return false
        }

        if (!isSpotifyInstalled()) {
            Log.e(TAG, "Spotify app is not installed — App Remote requires it")
            return false
        }

        return try {
            val params = ConnectionParams.Builder(clientId)
                .setRedirectUri("parachord://auth/callback/spotify")
                .showAuthView(true)
                .build()

            Log.d(TAG, "Connecting to Spotify App Remote...")
            appRemote = withTimeout(10_000L) {
                suspendCancellableCoroutine { cont ->
                    SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            Log.d(TAG, "Connected to Spotify App Remote")
                            cont.resume(remote)
                        }

                        override fun onFailure(error: Throwable) {
                            Log.e(TAG, "Spotify App Remote connection failed", error)
                            cont.resumeWithException(error)
                        }
                    })
                }
            }

            // Subscribe to player state updates
            appRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { state ->
                _playerState.value = state
            }

            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Spotify App Remote connection timed out (10s)")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Spotify", e)
            false
        }
    }

    private fun isSpotifyInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun disconnect() {
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
        _playerState.value = null
    }

    override suspend fun play(track: TrackEntity) {
        val uri = track.spotifyUri
        if (uri == null) {
            Log.w(TAG, "No spotifyUri on track '${track.title}' — cannot play")
            return
        }
        Log.d(TAG, "play() called for '${track.title}' uri=$uri connected=$isConnected")
        if (!isConnected) {
            if (!connect()) {
                Log.e(TAG, "Cannot play: not connected to Spotify")
                return
            }
        }
        Log.d(TAG, "Playing Spotify URI: $uri")
        appRemote?.playerApi?.play(uri)
    }

    override suspend fun pause() {
        appRemote?.playerApi?.pause()
    }

    override suspend fun resume() {
        appRemote?.playerApi?.resume()
    }

    override suspend fun seekTo(positionMs: Long) {
        appRemote?.playerApi?.seekTo(positionMs)
    }

    override suspend fun stop() {
        appRemote?.playerApi?.pause()
    }

    /** Get current playback position from Spotify. */
    fun getPosition(): Long = _playerState.value?.playbackPosition ?: 0L

    /** Get current track duration from Spotify. */
    fun getDuration(): Long = _playerState.value?.track?.duration ?: 0L

    /** Whether Spotify is currently playing. */
    fun isPlaying(): Boolean = _playerState.value?.isPaused == false
}
