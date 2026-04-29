package com.parachord.android.playback.scrobbler

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import okhttp3.OkHttpClient

/**
 * Libre.fm scrobbler — uses the same Last.fm-compatible API protocol
 * but against libre.fm/2.0/ with placeholder API key/secret.
 *
 * Mirrors the desktop app's librefm-scrobbler.js.
 * Auth is via auth.getMobileSession with username/password, producing a session key.
 *
 * The API key and shared secret are both the placeholder value
 * `00000000000000000000000000000000` as per Libre.fm convention.
 */
class LibreFmScrobbler constructor(
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
    private val lastFmScrobbler: LastFmScrobbler, // Reuse signing logic
) : Scrobbler {
    companion object {
        private const val TAG = "LibreFmScrobbler"
        private const val API_URL = "https://libre.fm/2.0/"
        private const val API_KEY = "00000000000000000000000000000000"
        private const val SHARED_SECRET = "00000000000000000000000000000000"
    }

    override val id = "librefm"
    override val displayName = "Libre.fm"

    override suspend fun isEnabled(): Boolean {
        return settingsStore.getLibreFmSessionKey() != null
    }

    override suspend fun sendNowPlaying(track: TrackEntity) {
        val sessionKey = settingsStore.getLibreFmSessionKey() ?: return
        try {
            val params = buildMap {
                put("method", "track.updateNowPlaying")
                put("artist", track.artist)
                put("track", track.title)
                put("api_key", API_KEY)
                put("sk", sessionKey)
                track.album?.let { put("album", it) }
                track.duration?.let { put("duration", (it / 1000).toString()) }
                track.recordingMbid?.let { put("mbid", it) }
            }

            val success = lastFmScrobbler.postSigned(params, API_URL, SHARED_SECRET)
            if (success) {
                Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send now playing", e)
        }
    }

    override suspend fun submitScrobble(track: TrackEntity, timestamp: Long) {
        val sessionKey = settingsStore.getLibreFmSessionKey() ?: return
        try {
            val params = buildMap {
                put("method", "track.scrobble")
                put("artist[0]", track.artist)
                put("track[0]", track.title)
                put("timestamp[0]", timestamp.toString())
                put("api_key", API_KEY)
                put("sk", sessionKey)
                track.album?.let { put("album[0]", it) }
                track.duration?.let { put("duration[0]", (it / 1000).toString()) }
                track.recordingMbid?.let { put("mbid[0]", it) }
            }

            val success = lastFmScrobbler.postSigned(params, API_URL, SHARED_SECRET)
            if (success) {
                Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scrobble", e)
        }
    }

    /**
     * Authenticate with Libre.fm using username/password via auth.getMobileSession.
     * Returns the session key on success, null on failure.
     */
    suspend fun authenticate(username: String, password: String): String? {
        return try {
            val params = buildMap {
                put("method", "auth.getMobileSession")
                put("username", username)
                put("password", password)
                put("api_key", API_KEY)
            }

            val allParams = params.toMutableMap()
            allParams["format"] = "json"

            // Generate api_sig
            val sigString = allParams
                .filterKeys { it != "format" }
                .toSortedMap()
                .entries
                .joinToString("") { "${it.key}${it.value}" } + SHARED_SECRET

            allParams["api_sig"] = lastFmScrobbler.md5(sigString)

            val formBody = okhttp3.FormBody.Builder().apply {
                allParams.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = okhttp3.Request.Builder()
                .url(API_URL)
                .post(formBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Auth failed ${response.code}: $body")
                return null
            }

            // Parse session key from JSON: {"session": {"key": "...", "name": "..."}}
            val json = org.json.JSONObject(body)
            if (json.has("error")) {
                Log.e(TAG, "Auth error: $body")
                return null
            }

            val sessionKey = json.getJSONObject("session").getString("key")
            settingsStore.setLibreFmSession(sessionKey)
            Log.d(TAG, "Libre.fm auth successful")
            sessionKey
        } catch (e: Exception) {
            Log.e(TAG, "Auth error", e)
            null
        }
    }
}
