package com.parachord.android.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.parachord.android.BuildConfig
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "OAuthManager"
        private const val REDIRECT_URI = "parachord://auth/callback"
    }

    // PKCE state for OAuth flows
    private var codeVerifier: String? = null

    // Track which service initiated the current PKCE flow
    private var pendingOAuthService: String? = null

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

    /** Launch Spotify OAuth flow with PKCE in a Custom Tab. */
    fun launchSpotifyAuth(clientId: String) {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        pendingOAuthService = "spotify"
        val challenge = generateCodeChallenge(verifier)

        val uri = Uri.parse("https://accounts.spotify.com/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "$REDIRECT_URI/spotify")
            .appendQueryParameter("scope", "user-read-playback-state user-modify-playback-state user-library-read")
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()

        launchCustomTab(uri)
    }

    /**
     * Launch SoundCloud OAuth 2.1 flow with PKCE in a Custom Tab.
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
        codeVerifier = verifier
        pendingOAuthService = "soundcloud"
        val challenge = generateCodeChallenge(verifier)

        val uri = Uri.parse("https://secure.soundcloud.com/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "$REDIRECT_URI/soundcloud")
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
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
        val verifier = codeVerifier ?: return false
        codeVerifier = null

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
        val verifier = codeVerifier ?: return false
        codeVerifier = null
        pendingOAuthService = null

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
        val intent = CustomTabsIntent.Builder().build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, uri)
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
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
