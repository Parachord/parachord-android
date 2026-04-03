package com.parachord.android.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.parachord.android.R
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject

/**
 * Foreground service that manages audio playback via ExoPlayer and exposes a
 * MediaSession for lock-screen controls, Bluetooth, and Android Auto.
 *
 * Also manages the Spotify App Remote connection lifecycle — connecting when
 * the service starts and disconnecting on destroy.
 *
 * Uses a unified notification style for both ExoPlayer (local/SoundCloud) and
 * external (Spotify/Apple Music) playback so the notification tray always looks
 * the same regardless of playback source.
 *
 * During external playback, ExoPlayer isn't actively playing so
 * MediaSessionService won't automatically keep the foreground notification.
 * We handle this by explicitly calling [startForeground] with a persistent
 * notification, preventing Android from killing the process.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var spotifyHandler: SpotifyPlaybackHandler
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var stateHolder: PlaybackStateHolder

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var isExternalForeground = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    /**
     * Pause playback when an audio output device disconnects (Bluetooth,
     * wired headset). Standard music player behavior — prevents audio from
     * blasting through the phone speaker unexpectedly.
     *
     * ExoPlayer handles this automatically when playing directly (via
     * handleAudioFocus), but during external playback ExoPlayer plays
     * silence with handleAudioFocus=false, so we need an explicit receiver.
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (device disconnected) — pausing playback")
                playbackController.togglePlayPause()
            }
        }
    }
    private var noisyReceiverRegistered = false

    /** Cached artwork bitmap for the current notification. */
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "parachord_playback"
        private const val ARTWORK_SIZE = 256

        /** Intent actions sent by PlaybackController to manage foreground state. */
        const val ACTION_EXTERNAL_PLAYBACK_START = "com.parachord.android.EXTERNAL_PLAYBACK_START"
        const val ACTION_EXTERNAL_PLAYBACK_STOP = "com.parachord.android.EXTERNAL_PLAYBACK_STOP"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_ARTIST = "track_artist"
        const val EXTRA_TRACK_ARTWORK_URL = "track_artwork_url"

        /** Notification action intents for playback controls. */
        const val ACTION_PLAY_PAUSE = "com.parachord.android.ACTION_PLAY_PAUSE"
        const val ACTION_SKIP_NEXT = "com.parachord.android.ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "com.parachord.android.ACTION_SKIP_PREVIOUS"
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

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

        // Unified notification provider — same look for ExoPlayer and external playback.
        setMediaNotificationProvider(UnifiedMediaNotificationProvider())

        // Pause on audio device disconnect (Bluetooth, wired headset)
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, noisyFilter)
        noisyReceiverRegistered = true

        startStateObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXTERNAL_PLAYBACK_START -> {
                val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: "Playing"
                val artist = intent.getStringExtra(EXTRA_TRACK_ARTIST) ?: ""
                val artworkUrl = intent.getStringExtra(EXTRA_TRACK_ARTWORK_URL)
                promoteToForeground(title, artist, artworkUrl)
            }
            ACTION_EXTERNAL_PLAYBACK_STOP -> {
                demoteFromForeground()
            }
            ACTION_PLAY_PAUSE -> {
                playbackController.togglePlayPause()
            }
            ACTION_SKIP_NEXT -> {
                playbackController.skipNext()
            }
            ACTION_SKIP_PREVIOUS -> {
                playbackController.skipPrevious()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Override Media3's auto-foreground management. Without this, MediaSessionService
     * calls stopForeground() when ExoPlayer reports "not playing" — which it always
     * does during external playback (Spotify/Apple Music) since ExoPlayer just holds
     * metadata. This caused Android to kill the process with the screen off.
     *
     * When [isExternalForeground] is true, we manage foreground ourselves via
     * [promoteToForeground]/[demoteFromForeground] and skip Media3's auto-management.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (isExternalForeground) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onUpdateNotification(session: MediaSession) {
        if (isExternalForeground) return
        super.onUpdateNotification(session)
    }

    /**
     * Prevent Media3 from calling stopSelf() when ExoPlayer is idle.
     * MediaSessionService calls this when it thinks nothing is playing and
     * the service should shut down. During external playback, music IS playing
     * (via Spotify/Apple Music) — ExoPlayer just doesn't know about it.
     */
    override fun pauseAllPlayersAndStopSelf() {
        if (isExternalForeground) {
            Log.d(TAG, "Blocked pauseAllPlayersAndStopSelf — external playback is active")
            return
        }
        super.pauseAllPlayersAndStopSelf()
    }

    /**
     * Don't stop when the user swipes away the app — keep playing.
     * This is critical for external playback (Spotify/Apple Music) where
     * the user expects music to continue in the background.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (isExternalForeground || (p != null && p.playWhenReady)) {
            // External playback active or ExoPlayer playing — keep alive
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            noisyReceiverRegistered = false
        }
        stateObserverJob?.cancel()
        serviceScope.cancel()
        spotifyHandler.disconnect()
        isExternalForeground = false
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    /**
     * Observe playback state changes to keep the external notification
     * in sync with the current track and play/pause state.
     */
    private fun startStateObserver() {
        stateObserverJob = serviceScope.launch {
            stateHolder.state.collectLatest { state ->
                if (!isExternalForeground) return@collectLatest

                val track = state.effectiveTrack ?: return@collectLatest
                val title = track.title
                val artist = track.artist ?: ""
                val artworkUrl = track.artworkUrl

                // Load artwork if URL changed
                if (artworkUrl != currentArtworkUrl) {
                    currentArtworkUrl = artworkUrl
                    currentArtworkBitmap = if (artworkUrl != null) {
                        fetchArtworkBitmap(artworkUrl)
                    } else null
                }

                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(
                    NOTIFICATION_ID,
                    buildNotification(title, artist, state.isPlaying, currentArtworkBitmap),
                )
            }
        }
    }

    /**
     * Explicitly start this service in the foreground during external playback.
     * MediaSessionService only auto-promotes when ExoPlayer is actively playing,
     * which doesn't happen for Spotify/Apple Music external handlers.
     */
    private fun promoteToForeground(title: String, artist: String, artworkUrl: String?) {
        if (isExternalForeground) {
            // Just update the notification — state observer will handle ongoing updates
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(
                NOTIFICATION_ID,
                buildNotification(title, artist, true, currentArtworkBitmap),
            )
            // Kick off artwork load if needed
            if (artworkUrl != currentArtworkUrl) {
                loadArtworkAndUpdateNotification(title, artist, artworkUrl)
            }
            return
        }

        Log.d(TAG, "Promoting to foreground for external playback: $title - $artist")
        val notification = buildNotification(title, artist, true, null)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else 0,
            )
            isExternalForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }

        // Load artwork async and update notification once ready
        if (artworkUrl != null) {
            loadArtworkAndUpdateNotification(title, artist, artworkUrl)
        }
    }

    private fun loadArtworkAndUpdateNotification(title: String, artist: String, artworkUrl: String?) {
        currentArtworkUrl = artworkUrl
        if (artworkUrl == null) {
            currentArtworkBitmap = null
            return
        }
        serviceScope.launch {
            val bitmap = fetchArtworkBitmap(artworkUrl)
            if (currentArtworkUrl == artworkUrl) {
                currentArtworkBitmap = bitmap
                if (isExternalForeground) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(
                        NOTIFICATION_ID,
                        buildNotification(title, artist, stateHolder.state.value.isPlaying, bitmap),
                    )
                }
            }
        }
    }

    private fun demoteFromForeground() {
        if (!isExternalForeground) return
        Log.d(TAG, "Demoting from external playback foreground")
        isExternalForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Build a media notification with consistent styling used for both
     * ExoPlayer and external (Spotify/Apple Music) playback.
     */
    internal fun buildNotification(
        title: String,
        artist: String,
        isPlaying: Boolean = true,
        artwork: Bitmap? = null,
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val skipPrevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_SKIP_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipNextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_SKIP_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_widget_skip_previous, "Previous", skipPrevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(R.drawable.ic_widget_skip_next, "Next", skipNextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession?.sessionCompatToken)
            )

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        return builder.build()
    }

    private suspend fun fetchArtworkBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val stream = connection.getInputStream()
            val original = BitmapFactory.decodeStream(stream)
            stream.close()
            if (original != null && (original.width > ARTWORK_SIZE || original.height > ARTWORK_SIZE)) {
                Bitmap.createScaledBitmap(original, ARTWORK_SIZE, ARTWORK_SIZE, true).also {
                    if (it !== original) original.recycle()
                }
            } else {
                original
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch notification artwork: $url", e)
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Now Playing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the current track and playback controls"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    /**
     * Custom [MediaNotification.Provider] that builds notifications using our
     * unified [buildNotification] style. This ensures ExoPlayer playback
     * (local files, SoundCloud) and external playback (Spotify, Apple Music)
     * show identical notifications in the tray.
     *
     * Media3's [DefaultMediaNotificationProvider] is replaced by this so
     * there's no visual difference between playback sources.
     */
    private inner class UnifiedMediaNotificationProvider : MediaNotification.Provider {

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val player = mediaSession.player
            val metadata = player.mediaMetadata
            val title = metadata.title?.toString() ?: ""
            val artist = metadata.artist?.toString() ?: ""
            val isPlaying = player.isPlaying

            // Load artwork from the MediaMetadata artworkUri if we don't have it cached
            val artworkUri = metadata.artworkUri
            val artworkUrl = artworkUri?.toString()
            if (artworkUrl != null && artworkUrl != currentArtworkUrl) {
                currentArtworkUrl = artworkUrl
                currentArtworkBitmap = null
                // Fetch async and re-post via callback
                serviceScope.launch {
                    val bitmap = fetchArtworkBitmap(artworkUrl)
                    if (currentArtworkUrl == artworkUrl) {
                        currentArtworkBitmap = bitmap
                        val updated = buildNotification(title, artist, isPlaying, bitmap)
                        onNotificationChangedCallback.onNotificationChanged(
                            MediaNotification(NOTIFICATION_ID, updated)
                        )
                    }
                }
            }

            val notification = buildNotification(title, artist, isPlaying, currentArtworkBitmap)
            return MediaNotification(NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false
    }
}
