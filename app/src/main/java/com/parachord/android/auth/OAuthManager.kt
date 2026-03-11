package com.parachord.android.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages OAuth authentication flows using Android Custom Tabs.
 * Replaces the Express server OAuth approach from the desktop Electron app.
 *
 * Deep-link callbacks are handled via the parachord://auth/* intent filter
 * declared in AndroidManifest.xml.
 */
@Singleton
class OAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val REDIRECT_URI = "parachord://auth/callback"
    }

    /** Launch Spotify OAuth flow in a Custom Tab. */
    fun launchSpotifyAuth(clientId: String) {
        val uri = Uri.parse("https://accounts.spotify.com/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "$REDIRECT_URI/spotify")
            .appendQueryParameter("scope", "user-read-playback-state user-modify-playback-state user-library-read")
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

    /** Handle the OAuth redirect deep link and extract tokens. */
    suspend fun handleRedirect(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return when {
            path.contains("spotify") -> handleSpotifyCallback(uri)
            path.contains("lastfm") -> handleLastFmCallback(uri)
            else -> false
        }
    }

    private suspend fun handleSpotifyCallback(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        // TODO: Exchange code for tokens via Spotify token endpoint
        return true
    }

    private suspend fun handleLastFmCallback(uri: Uri): Boolean {
        val token = uri.getQueryParameter("token") ?: return false
        // TODO: Exchange token for session key via Last.fm API
        return true
    }

    private fun launchCustomTab(uri: Uri) {
        val intent = CustomTabsIntent.Builder().build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, uri)
    }
}
