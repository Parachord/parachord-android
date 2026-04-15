package com.parachord.android.playback.handlers

import android.util.Log
import androidx.media3.common.MediaItem
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Handles SoundCloud playback by fetching stream URLs from the SoundCloud API
 * and routing them through ExoPlayer.
 *
 * Mirrors the desktop app's approach: fetch audio via /tracks/{id}/stream or
 * /tracks/{id}/streams, then play the MP3 URL through the native audio engine.
 * Unlike the desktop (which needs a proxy fetch + blob URL for CORS), Android
 * can play the stream URLs directly via ExoPlayer.
 */
class SoundCloudPlaybackHandler constructor(
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : SourceHandler {

    companion object {
        private const val TAG = "SoundCloudPlayback"
        private const val SC_API_BASE = "https://api.soundcloud.com"
    }

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "soundcloud" && track.soundcloudId != null

    override suspend fun createMediaItem(track: TrackEntity): MediaItem? {
        val scId = track.soundcloudId ?: return null
        val token = settingsStore.getSoundCloudToken()
        if (token.isNullOrBlank()) {
            Log.e(TAG, "No SoundCloud token available")
            return null
        }

        val streamUrl = resolveStreamUrl(scId, token) ?: return null

        Log.d(TAG, "Resolved SoundCloud stream for track ${track.title}")
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl)
            .build()
    }

    /**
     * Resolve a direct stream URL for a SoundCloud track.
     * Tries the /stream endpoint first, then falls back to /streams.
     */
    private fun resolveStreamUrl(trackId: String, token: String): String? {
        // Try direct /stream endpoint (older API, returns redirect to MP3)
        val directUrl = "$SC_API_BASE/tracks/$trackId/stream?oauth_token=$token"
        try {
            val request = Request.Builder()
                .url(directUrl)
                .head()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Direct stream URL available")
                return directUrl
            }
            val status = response.code
            Log.d(TAG, "Direct stream returned $status, trying /streams endpoint")
        } catch (e: Exception) {
            Log.d(TAG, "Direct stream test failed: ${e.message}")
        }

        // Fall back to /streams endpoint which returns pre-signed URLs
        try {
            val request = Request.Builder()
                .url("$SC_API_BASE/tracks/$trackId/streams")
                .header("Authorization", "OAuth $token")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Streams endpoint failed with ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val streams = json.decodeFromString<SoundCloudStreams>(body)

            // Prefer direct HTTP MP3 over HLS or preview
            val streamUrl = streams.httpMp3128Url ?: streams.previewMp3128Url
            if (streamUrl != null) {
                Log.d(TAG, "Using pre-signed stream URL from /streams")
            }
            return streamUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream URL", e)
            return null
        }
    }
}

@Serializable
private data class SoundCloudStreams(
    @SerialName("http_mp3_128_url") val httpMp3128Url: String? = null,
    @SerialName("hls_mp3_128_url") val hlsMp3128Url: String? = null,
    @SerialName("preview_mp3_128_url") val previewMp3128Url: String? = null,
)
