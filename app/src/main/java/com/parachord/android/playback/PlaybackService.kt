package com.parachord.android.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that manages audio playback via ExoPlayer and exposes a
 * MediaSession for lock-screen controls, Bluetooth, and Android Auto.
 *
 * Also manages the Spotify App Remote connection lifecycle — connecting when
 * the service starts and disconnecting on destroy.
 *
 * During external playback (Spotify Connect, Apple Music), ExoPlayer is prepared
 * with track metadata but not actually playing. This keeps the MediaSession active
 * and the foreground notification visible, preventing Android from killing the
 * process when the screen is off.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var spotifyHandler: SpotifyPlaybackHandler

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exoPlayer

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Don't stop when the user swipes away the app — keep playing.
     * This is critical for external playback (Spotify/Apple Music) where
     * the user expects music to continue in the background.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p != null && (p.playWhenReady || p.mediaItemCount > 0)) {
            // Player has content (either playing via ExoPlayer or holding
            // metadata for external playback) — keep the service alive
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        spotifyHandler.disconnect()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
