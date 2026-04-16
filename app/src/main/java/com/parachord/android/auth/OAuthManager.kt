package com.parachord.android.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.parachord.android.BuildConfig
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

class OAuthManager constructor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "OAuthManager"
        private const val REDIRECT_URI = "parachord://auth/callback"
    }

    /**
     * PKCE + CSRF state for in-flight OAuth flows, keyed by the OAuth `state`
     * parameter. Persisted to SecureTokenStore so flows survive app-kill between
     * launching the Custom Tab and receiving the callback.
     *
     * security: H5 (state/CSRF), L4 (persistence across app-kill)
     */
    private val pendingFlows = mutableMapOf<String, PendingOAuthFlow>()

    init {
        // Restore any pending flows from a previous session (security: L4)
        restorePendingFlows()
    }

    private fun restorePendingFlows() {
        try {
            val raw = settingsStore.secureStore.get("oauth_pending_flows") ?: return
            val restored = json.decodeFromString<Map<String, PendingOAuthFlow>>(raw)
            pendingFlows.putAll(restored)
            Log.d(TAG, "Restored ${restored.size} pending OAuth flow(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore pending flows: ${e.message}")
        }
    }

    private fun persistPendingFlows() {
        try {
            if (pendingFlows.isEmpty()) {
                settingsStore.secureStore.remove("oauth_pending_flows")
            } else {
                settingsStore.secureStore.set(
                    "oauth_pending_flows",
                    json.encodeToString(pendingFlows.toMap()),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist pending flows: ${e.message}")
        }
    }

    /** Mutex to prevent concurrent refresh attempts. */
    private val refreshMutex = Mutex()
    private val scRefreshMutex = Mutex()

    /**
     * Refresh the Spotify access token using the stored refresh token.
     * Returns true if the token was refreshed successfully.
     * Called automatically by SpotifyProvider and ResolverManager on HTTP 401.
     * Uses Dispatchers.IO since OkHttp execute() is a blocking call.
     */
    suspend fun refreshSpotifyToken(): Boolean = refreshMutex.withLock {
        val refreshToken = settingsStore.getSpotifyRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "No refresh token stored — re-auth required")
            return false
        }
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        if (clientId.isBlank()) {
            Log.w(TAG, "No Spotify client ID configured")
            return false
        }

        return try {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", clientId)
                    .build()

                val request = Request.Builder()
                    .url("https://accounts.spotify.com/api/token")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody == null) {
                    Log.e(TAG, "Spotify token refresh: empty response body")
                    return@withContext false
                }

                if (!response.isSuccessful) {
                    Log.e(TAG, "Spotify token refresh failed (${response.code}): $responseBody")
                    return@withContext false
                }

                val tokenResponse = json.decodeFromString<SpotifyRefreshResponse>(responseBody)
                settingsStore.setSpotifyTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: refreshToken, // keep old if not returned
                )
                Log.d(TAG, "Spotify token refreshed successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spotify token refresh error", e)
            false
        }
    }

    /** Launch Spotify OAuth flow with PKCE + state CSRF protection in a Custom Tab. */
    fun launchSpotifyAuth(clientId: String) {
        val verifier = generateCodeVerifier()
        val state = generateOAuthState()
        pendingFlows[state] = PendingOAuthFlow(
            service = "spotify",
            codeVerifier = verifier,
            createdAt = System.currentTimeMillis(),
        )
        prunePendingFlows()
        val challenge = generateCodeChallenge(verifier)

        val uri = Uri.parse("https://accounts.spotify.com/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "$REDIRECT_URI/spotify")
            .appendQueryParameter("scope", listOf(
                "user-read-playback-state",
                "user-modify-playback-state",
                "user-read-private",
                "user-library-read",
                "user-library-modify",
                "user-follow-read",
                "user-follow-modify",
                "playlist-read-private",
                "playlist-read-collaborative",
                "playlist-modify-public",
                "playlist-modify-private",
            ).joinToString(" "))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()

        launchCustomTab(uri)
    }

    /**
     * Launch SoundCloud OAuth 2.1 flow with PKCE + state in a Custom Tab.
     * Uses the user's own client ID from settings, or falls back to BuildConfig.
     */
    suspend fun launchSoundCloudAuth() {
        val clientId = settingsStore.getSoundCloudClientId()
            ?: BuildConfig.SOUNDCLOUD_CLIENT_ID.ifBlank { null }
        if (clientId == null) {
            Log.w(TAG, "No SoundCloud client ID available")
            return
        }

        val verifier = generateCodeVerifier()
        val state = generateOAuthState()
        pendingFlows[state] = PendingOAuthFlow(
            service = "soundcloud",
            codeVerifier = verifier,
            createdAt = System.currentTimeMillis(),
        )
        prunePendingFlows()
        val challenge = generateCodeChallenge(verifier)

        val uri = Uri.parse("https://secure.soundcloud.com/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "$REDIRECT_URI/soundcloud")
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()

        launchCustomTab(uri)
    }

    /** Launch Last.fm authentication flow in a Custom Tab. */
    fun launchLastFmAuth(apiKey: String) {
        val uri = Uri.parse("https://www.last.fm/api/auth/")
            .buildUpon()
            .appendQueryParameter("api_key", apiKey)
            .appendQueryParameter("cb", "$REDIRECT_URI/lastfm")
            .build()

        launchCustomTab(uri)
    }

    /** Handle the OAuth redirect deep link and exchange for tokens. */
    suspend fun handleRedirect(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return when {
            path.contains("spotify") -> handleSpotifyCallback(uri)
            path.contains("soundcloud") -> handleSoundCloudCallback(uri)
            path.contains("lastfm") -> handleLastFmCallback(uri)
            else -> false
        }
    }

    private suspend fun handleSpotifyCallback(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        val state = uri.getQueryParameter("state")
        if (state == null) {
            Log.w(TAG, "Spotify callback missing state parameter — rejecting")
            return false
        }
        val flow = pendingFlows.remove(state)
        persistPendingFlows()
        if (flow == null || flow.service != "spotify") {
            Log.w(TAG, "Spotify callback state mismatch — possible CSRF; rejecting")
            return false
        }
        val verifier = flow.codeVerifier

        return try {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", "$REDIRECT_URI/spotify")
                .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return false

            if (!response.isSuccessful) {
                Log.e(TAG, "Spotify token exchange failed: $responseBody")
                return false
            }

            val tokenResponse = json.decodeFromString<SpotifyTokenResponse>(responseBody)
            settingsStore.setSpotifyTokens(tokenResponse.accessToken, tokenResponse.refreshToken ?: "")
            Log.d(TAG, "Spotify auth successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Spotify token exchange error", e)
            false
        }
    }

    private suspend fun handleSoundCloudCallback(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        val state = uri.getQueryParameter("state")
        if (state == null) {
            Log.w(TAG, "SoundCloud callback missing state parameter — rejecting")
            return false
        }
        val flow = pendingFlows.remove(state)
        persistPendingFlows()
        if (flow == null || flow.service != "soundcloud") {
            Log.w(TAG, "SoundCloud callback state mismatch — possible CSRF; rejecting")
            return false
        }
        val verifier = flow.codeVerifier

        return try {
            val clientId = settingsStore.getSoundCloudClientId()
                ?: BuildConfig.SOUNDCLOUD_CLIENT_ID
            val clientSecret = settingsStore.getSoundCloudClientSecret()
                ?: BuildConfig.SOUNDCLOUD_CLIENT_SECRET

            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", "$REDIRECT_URI/soundcloud")
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("code_verifier", verifier)
                    .build()

                val request = Request.Builder()
                    .url("https://secure.soundcloud.com/oauth/token")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext false

                if (!response.isSuccessful) {
                    Log.e(TAG, "SoundCloud token exchange failed (${response.code}): $responseBody")
                    return@withContext false
                }

                val tokenResponse = json.decodeFromString<SoundCloudTokenResponse>(responseBody)
                settingsStore.setSoundCloudTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: "",
                )
                Log.d(TAG, "SoundCloud auth successful")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "SoundCloud token exchange error", e)
            false
        }
    }

    /**
     * Refresh the SoundCloud access token using the stored refresh token.
     * Returns true if the token was refreshed successfully.
     */
    suspend fun refreshSoundCloudToken(): Boolean = scRefreshMutex.withLock {
        val refreshToken = settingsStore.getSoundCloudRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No SoundCloud refresh token stored — re-auth required")
            return false
        }
        val clientId = settingsStore.getSoundCloudClientId()
            ?: BuildConfig.SOUNDCLOUD_CLIENT_ID.ifBlank { null }
        val clientSecret = settingsStore.getSoundCloudClientSecret()
            ?: BuildConfig.SOUNDCLOUD_CLIENT_SECRET.ifBlank { null }
        if (clientId == null || clientSecret == null) {
            Log.w(TAG, "No SoundCloud client credentials configured")
            return false
        }

        return try {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .build()

                val request = Request.Builder()
                    .url("https://secure.soundcloud.com/oauth/token")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody == null) {
                    Log.e(TAG, "SoundCloud token refresh: empty response body")
                    return@withContext false
                }

                if (!response.isSuccessful) {
                    Log.e(TAG, "SoundCloud token refresh failed (${response.code}): $responseBody")
                    return@withContext false
                }

                val tokenResponse = json.decodeFromString<SoundCloudTokenResponse>(responseBody)
                settingsStore.setSoundCloudTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: refreshToken, // keep old if not returned
                )
                Log.d(TAG, "SoundCloud token refreshed successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "SoundCloud token refresh error", e)
            false
        }
    }

    private suspend fun handleLastFmCallback(uri: Uri): Boolean {
        val token = uri.getQueryParameter("token") ?: return false

        return try {
            val apiKey = BuildConfig.LASTFM_API_KEY
            val secret = BuildConfig.LASTFM_SHARED_SECRET

            // Build the API signature: md5(api_key{val}method{val}token{val}{secret})
            val sigInput = "api_key${apiKey}methodauth.getSessiontoken${token}${secret}"
            val apiSig = md5(sigInput)

            val url = Uri.parse("https://ws.audioscrobbler.com/2.0/")
                .buildUpon()
                .appendQueryParameter("method", "auth.getSession")
                .appendQueryParameter("api_key", apiKey)
                .appendQueryParameter("token", token)
                .appendQueryParameter("api_sig", apiSig)
                .appendQueryParameter("format", "json")
                .build()

            val request = Request.Builder().url(url.toString()).get().build()
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return false

            if (!response.isSuccessful) {
                Log.e(TAG, "Last.fm session exchange failed: $responseBody")
                return false
            }

            val sessionResponse = json.decodeFromString<LastFmSessionResponse>(responseBody)
            val sessionKey = sessionResponse.session?.key ?: return false
            settingsStore.setLastFmSession(sessionKey)
            sessionResponse.session.name?.let { settingsStore.setLastFmUsername(it) }
            Log.d(TAG, "Last.fm auth successful (user: ${sessionResponse.session.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Last.fm session exchange error", e)
            false
        }
    }

    private fun launchCustomTab(uri: Uri) {
        val customTabsIntent = CustomTabsIntent.Builder().build()
        // Prefer the currently-resumed Activity so the Chrome Custom Tab runs
        // in the same task as MainActivity. This lets Android pop Chrome off
        // the back stack when our OAuthRedirectActivity returns focus to
        // MainActivity after the OAuth callback — otherwise Chrome stays
        // alive in its own separate task. Fallback to application context
        // with NEW_TASK if no Activity is tracked (defensive; should always
        // have one since OAuth is initiated from the Settings screen).
        val activity = com.parachord.android.app.CurrentActivityHolder.current
        if (activity != null) {
            customTabsIntent.launchUrl(activity, uri)
        } else {
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate a cryptographically-random OAuth `state` parameter for CSRF
     * protection. 24 bytes = 192 bits of entropy, encoded URL-safe Base64.
     * security: H5
     */
    private fun generateOAuthState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    /**
     * Drop pending flows older than 10 minutes so a stale/abandoned launch
     * doesn't leak verifiers indefinitely.
     */
    private fun prunePendingFlows() {
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        pendingFlows.entries.removeAll { it.value.createdAt < cutoff }
        persistPendingFlows()
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * In-flight OAuth flow state keyed by the `state` parameter.
 * security: H5
 */
@Serializable
private data class PendingOAuthFlow(
    val service: String,
    val codeVerifier: String,
    val createdAt: Long,
)

@Serializable
private data class SpotifyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class SpotifyRefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class SoundCloudTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class LastFmSessionResponse(
    val session: LastFmSession? = null,
)

@Serializable
private data class LastFmSession(
    val key: String,
    val name: String? = null,
)
