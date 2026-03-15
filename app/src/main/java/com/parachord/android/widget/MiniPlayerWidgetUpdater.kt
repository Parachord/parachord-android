package com.parachord.android.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes [PlaybackStateHolder] and pushes updates to the mini player widget.
 *
 * Also registers a broadcast receiver for widget button presses and forwards
 * them to [PlaybackController]. This lives as a Hilt singleton so it can
 * access injected dependencies that the plain [AppWidgetProvider] cannot.
 */
@Singleton
class MiniPlayerWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: PlaybackStateHolder,
    private val playbackController: PlaybackController,
) {
    companion object {
        private const val TAG = "WidgetUpdater"

        // Cached last-known state so the widget provider can read it
        // during onUpdate() without needing Hilt.
        @Volatile var lastTitle: String? = null
        @Volatile var lastArtist: String? = null
        @Volatile var lastIsPlaying: Boolean = false
        @Volatile var lastProgress: Float = 0f
        @Volatile var lastArtworkBitmap: Bitmap? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null

    /** Simple in-memory cache for decoded artwork bitmaps keyed by URL. */
    private val artworkCache = LruCache<String, Bitmap>(5)
    private var lastArtworkUrl: String? = null

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getStringExtra("action")) {
                "play_pause" -> playbackController.togglePlayPause()
                "skip_next" -> playbackController.skipNext()
                "skip_previous" -> playbackController.skipPrevious()
            }
        }
    }

    /** Start observing playback state and pushing widget updates. */
    fun startObserving() {
        if (observeJob != null) return

        // Register for widget media action broadcasts
        val filter = IntentFilter("com.parachord.android.WIDGET_MEDIA_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(mediaActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(mediaActionReceiver, filter)
        }

        observeJob = scope.launch {
            stateHolder.state.collectLatest { state ->
                val track = state.currentTrack
                val title = track?.title
                val artist = track?.artist
                val isPlaying = state.isPlaying
                val progress = if (state.duration > 0) {
                    state.position.toFloat() / state.duration.toFloat()
                } else {
                    0f
                }

                // Update cached state
                lastTitle = title
                lastArtist = artist
                lastIsPlaying = isPlaying
                lastProgress = progress

                // Fetch artwork if URL changed
                val artworkUrl = track?.artworkUrl
                if (artworkUrl != lastArtworkUrl) {
                    lastArtworkUrl = artworkUrl
                    lastArtworkBitmap = if (artworkUrl != null) {
                        fetchArtwork(artworkUrl)
                    } else {
                        null
                    }
                }

                pushUpdate()
            }
        }

        // Also run a periodic position update for the progress bar
        // (state only emits on changes, but position updates may be batched)
        scope.launch {
            while (true) {
                delay(2000)
                val state = stateHolder.state.value
                if (state.isPlaying && state.duration > 0) {
                    lastProgress = state.position.toFloat() / state.duration.toFloat()
                    pushUpdate()
                }
            }
        }
    }

    private fun pushUpdate() {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, MiniPlayerWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return

        val views = MiniPlayerWidgetProvider.buildRemoteViews(
            context,
            title = lastTitle,
            artist = lastArtist,
            isPlaying = lastIsPlaying,
            progress = lastProgress,
            artworkBitmap = lastArtworkBitmap,
        )

        for (id in ids) {
            manager.updateAppWidget(id, views)
        }
    }

    private suspend fun fetchArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        // Check cache first
        artworkCache.get(url)?.let { return@withContext it }

        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val stream = connection.getInputStream()
            val original = BitmapFactory.decodeStream(stream)
            stream.close()

            // Scale down for widget display (48dp ~ 144px at xxxhdpi)
            val scaled = if (original != null && (original.width > 200 || original.height > 200)) {
                Bitmap.createScaledBitmap(original, 144, 144, true).also {
                    if (it !== original) original.recycle()
                }
            } else {
                original
            }

            scaled?.let { artworkCache.put(url, it) }
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch widget artwork: $url", e)
            null
        }
    }
}
