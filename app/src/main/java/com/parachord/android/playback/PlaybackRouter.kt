package com.parachord.android.playback

import android.util.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.DirectStreamHandler
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.LocalFileHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.playback.handlers.SoundCloudPlaybackHandler
import com.parachord.android.playback.handlers.SourceHandler
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes tracks to the appropriate playback handler based on resolver type.
 *
 * The routing order matches the desktop app's logic:
 * 1. Spotify tracks → Spotify App Remote (external playback)
 * 2. SoundCloud tracks → Stream URL resolution + ExoPlayer
 * 3. Local files → ExoPlayer with content:// URI
 * 4. Direct streams → ExoPlayer with HTTP URL
 */
@Singleton
class PlaybackRouter @Inject constructor(
    private val spotifyHandler: SpotifyPlaybackHandler,
    private val soundCloudHandler: SoundCloudPlaybackHandler,
) {
    companion object {
        private const val TAG = "PlaybackRouter"
    }

    private val localFileHandler = LocalFileHandler()
    private val directStreamHandler = DirectStreamHandler()

    /** The handler currently managing external playback, if any. */
    var activeExternalHandler: ExternalPlaybackHandler? = null
        private set

    /** All handlers in priority order. */
    private val handlers: List<SourceHandler>
        get() = listOf(spotifyHandler, soundCloudHandler, localFileHandler, directStreamHandler)

    /**
     * Determine how to play a track and return the appropriate action.
     * Returns null if no handler can play this track.
     */
    suspend fun route(track: TrackEntity): PlaybackAction? {
        // Find the first handler that can handle this track
        for (handler in handlers) {
            if (!handler.canHandle(track)) continue

            Log.d(TAG, "Routing '${track.title}' to ${handler::class.simpleName}")

            // Spotify is external playback (App Remote controls Spotify app)
            if (handler is SpotifyPlaybackHandler) {
                // Stop any previous external handler
                stopExternalPlayback()
                activeExternalHandler = handler
                return PlaybackAction.ExternalPlayback(handler)
            }

            // All other handlers produce MediaItems for ExoPlayer
            val mediaItem = handler.createMediaItem(track)
            if (mediaItem != null) {
                stopExternalPlayback()
                return PlaybackAction.ExoPlayerItem(mediaItem)
            }
        }

        Log.w(TAG, "No handler found for track: ${track.title} (resolver=${track.resolver}, sourceType=${track.sourceType})")
        return null
    }

    /** Stop any externally-managed playback. */
    suspend fun stopExternalPlayback() {
        activeExternalHandler?.stop()
        activeExternalHandler = null
    }

    /** Get the Spotify handler for state observation. */
    fun getSpotifyHandler(): SpotifyPlaybackHandler = spotifyHandler
}
