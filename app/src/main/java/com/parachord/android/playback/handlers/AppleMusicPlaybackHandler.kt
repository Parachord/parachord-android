package com.parachord.android.playback.handlers

import android.util.Log
import com.parachord.android.data.db.entity.TrackEntity
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
    private val musicKitBridge: MusicKitWebBridge,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "AppleMusicHandler"
    }

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "applemusic" && track.appleMusicId != null

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    override suspend fun play(track: TrackEntity) {
        val songId = track.appleMusicId ?: return
        // Ensure configured + authorized
        if (!musicKitBridge.configured.value) {
            if (!musicKitBridge.configure()) {
                Log.w(TAG, "MusicKit configuration failed")
                return
            }
        }
        if (!musicKitBridge.authorized.value) {
            if (!musicKitBridge.authorize()) {
                Log.w(TAG, "MusicKit authorization failed")
                return
            }
        }
        musicKitBridge.play(songId)
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
