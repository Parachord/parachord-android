package com.parachord.android.playback.scrobbler

import android.util.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ListenBrainz scrobbler — sends JSON POST requests to api.listenbrainz.org.
 *
 * Mirrors the desktop app's listenbrainz-scrobbler.js.
 * Auth is a simple user token passed as `Authorization: Token {token}`.
 *
 * API docs: https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
 */
@Singleton
class ListenBrainzScrobbler @Inject constructor(
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) : Scrobbler {
    companion object {
        private const val TAG = "ListenBrainzScrobbler"
        private const val API_URL = "https://api.listenbrainz.org/1/submit-listens"
    }

    override val id = "listenbrainz"
    override val displayName = "ListenBrainz"

    override suspend fun isEnabled(): Boolean {
        return settingsStore.getListenBrainzToken() != null
    }

    override suspend fun sendNowPlaying(track: TrackEntity) {
        val token = settingsStore.getListenBrainzToken() ?: return
        try {
            val payload = buildPayload(
                listenType = "playing_now",
                track = track,
                timestamp = null,
            )

            val success = post(payload, token)
            if (success) {
                Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send now playing", e)
        }
    }

    override suspend fun submitScrobble(track: TrackEntity, timestamp: Long) {
        val token = settingsStore.getListenBrainzToken() ?: return
        try {
            val payload = buildPayload(
                listenType = "single",
                track = track,
                timestamp = timestamp,
            )

            val success = post(payload, token)
            if (success) {
                Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scrobble", e)
        }
    }

    /**
     * Build the ListenBrainz JSON payload.
     *
     * Format from desktop's listenbrainz-scrobbler.js:
     * ```json
     * {
     *   "listen_type": "single" | "playing_now",
     *   "payload": [{
     *     "listened_at": 1234567890,  // omitted for playing_now
     *     "track_metadata": {
     *       "artist_name": "...",
     *       "track_name": "...",
     *       "release_name": "...",
     *       "additional_info": {
     *         "media_player": "Parachord"
     *       }
     *     }
     *   }]
     * }
     * ```
     */
    private fun buildPayload(
        listenType: String,
        track: TrackEntity,
        timestamp: Long?,
    ): JSONObject {
        val additionalInfo = JSONObject().apply {
            put("media_player", "Parachord")
            // Include MBIDs when available — improves match accuracy on ListenBrainz
            track.recordingMbid?.let { put("recording_mbid", it) }
            track.artistMbid?.let {
                put("artist_mbids", JSONArray().put(it))
            }
            track.releaseMbid?.let { put("release_mbid", it) }
        }

        val trackMetadata = JSONObject().apply {
            put("artist_name", track.artist)
            put("track_name", track.title)
            track.album?.let { put("release_name", it) }
            // Top-level MBIDs per ListenBrainz API spec
            track.recordingMbid?.let { put("recording_mbid", it) }
            put("additional_info", additionalInfo)
        }

        val listenObj = JSONObject().apply {
            if (timestamp != null) {
                put("listened_at", timestamp)
            }
            put("track_metadata", trackMetadata)
        }

        return JSONObject().apply {
            put("listen_type", listenType)
            put("payload", JSONArray().put(listenObj))
        }
    }

    private fun post(payload: JSONObject, token: String): Boolean {
        val body = payload.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Token $token")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful) {
            Log.e(TAG, "API error ${response.code}: $responseBody")
            return false
        }

        return true
    }
}
