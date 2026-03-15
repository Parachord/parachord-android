package com.parachord.android.playback.handlers

import android.util.Log
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Apple Music playback via MusicKit JS inside a hidden WebView.
 *
 * Like Spotify, Apple Music is an external playback handler — DRM-protected
 * streaming is managed by MusicKit JS, not ExoPlayer. The [MusicKitWebBridge]
 * handles all communication with the WebView.
 */
@Singleton
class AppleMusicPlaybackHandler @Inject constructor(
    val musicKitBridge: MusicKitWebBridge,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "AppleMusicHandler"
    }

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "applemusic" && track.appleMusicId != null

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    /** Whether the user has completed a full authorize flow this session. */
    private var hasAuthorizedThisSession = false

    override suspend fun play(track: TrackEntity) {
        val songId = track.appleMusicId ?: return
        // Ensure configured + authorized
        if (!musicKitBridge.configured.value) {
            if (!musicKitBridge.configure()) {
                Log.w(TAG, "MusicKit configuration failed")
                return
            }
        }
        // Always do a full authorize on first play of the session.
        // MusicKit may auto-restore auth from cookies during configure() but
        // the cached token may lack Apple Music subscription entitlements,
        // resulting in 30-second preview playback instead of full tracks.
        if (!hasAuthorizedThisSession || !musicKitBridge.authorized.value) {
            Log.d(TAG, "Authorizing (firstSession=${!hasAuthorizedThisSession}, authorized=${musicKitBridge.authorized.value})")
            if (!musicKitBridge.authorize()) {
                Log.w(TAG, "MusicKit authorization required — prompting user")
                musicKitBridge.emitSignInRequired()
                return
            }
            hasAuthorizedThisSession = true
        }
        var success = musicKitBridge.play(songId)
        if (!success) {
            // Widevine DRM license negotiation can take a moment after auth.
            // Retry a few times with a short delay before falling back to re-auth.
            Log.w(TAG, "MusicKit playback failed, retrying after delay (DRM handshake may be in progress)")
            for (attempt in 1..3) {
                delay(1000L * attempt)
                success = musicKitBridge.play(songId)
                if (success) {
                    Log.d(TAG, "Play succeeded on retry attempt $attempt")
                    break
                }
            }
            if (!success) {
                // Retries exhausted — likely an expired user token, re-authorize
                Log.w(TAG, "MusicKit playback still failing, attempting re-authorization")
                hasAuthorizedThisSession = false
                if (musicKitBridge.authorize()) {
                    hasAuthorizedThisSession = true
                    musicKitBridge.play(songId)
                } else {
                    musicKitBridge.emitSignInRequired()
                }
            }
        }
    }

    override suspend fun pause() { musicKitBridge.pause() }
    override suspend fun resume() { musicKitBridge.resume() }
    override suspend fun seekTo(positionMs: Long) { musicKitBridge.seekTo(positionMs) }
    override suspend fun stop() { musicKitBridge.stop() }

    override val isConnected: Boolean
        get() = musicKitBridge.configured.value

    fun getPosition(): Long = musicKitBridge.getPosition()
    fun getDuration(): Long = musicKitBridge.getDuration()
    fun isPlaying(): Boolean = musicKitBridge.getIsPlaying()

    /**
     * Detect whether the Apple Music track has finished playing.
     * Simpler than Spotify's detection since MusicKit JS reports state directly.
     */
    fun isOurTrackDone(): Boolean {
        val duration = getDuration()
        val position = getPosition()
        if (duration <= 0) return false
        return !isPlaying() && position > 0 && duration - position < 1500
    }
}
