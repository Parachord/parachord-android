package com.parachord.android.playback.handlers

import androidx.media3.common.MediaItem

/**
 * Routes resolved sources to appropriate playback mechanisms.
 *
 * Each handler knows how to create a MediaItem (or trigger an external app)
 * for its source type:
 * - DirectStreamHandler: ExoPlayer with URL + headers
 * - SpotifyHandler: Spotify App Remote or Connect API
 * - YouTubeHandler: WebView or YouTube app intent
 * - LocalFileHandler: ExoPlayer with content:// URI
 */
interface SourceHandler {
    /** Whether this handler can play the given source type. */
    fun canHandle(sourceType: String): Boolean

    /** Create a MediaItem for ExoPlayer, or null if playback is external. */
    suspend fun createMediaItem(sourceUrl: String, headers: Map<String, String> = emptyMap()): MediaItem?
}

class DirectStreamHandler : SourceHandler {
    override fun canHandle(sourceType: String): Boolean =
        sourceType == "direct" || sourceType == "stream"

    override suspend fun createMediaItem(sourceUrl: String, headers: Map<String, String>): MediaItem =
        MediaItem.Builder()
            .setUri(sourceUrl)
            .build()
}

class LocalFileHandler : SourceHandler {
    override fun canHandle(sourceType: String): Boolean =
        sourceType == "local"

    override suspend fun createMediaItem(sourceUrl: String, headers: Map<String, String>): MediaItem =
        MediaItem.Builder()
            .setUri(sourceUrl)
            .build()
}
