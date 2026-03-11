package com.parachord.android.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences store replacing electron-store from the desktop app.
 * Uses Jetpack DataStore for type-safe, async key-value storage.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SCROBBLING_ENABLED = booleanPreferencesKey("scrobbling_enabled")
        val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        val SPOTIFY_ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        val SPOTIFY_REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        val SOUNDCLOUD_ACCESS_TOKEN = stringPreferencesKey("soundcloud_access_token")
    }

    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val scrobblingEnabled: Flow<Boolean> = dataStore.data.map { it[SCROBBLING_ENABLED] ?: false }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setScrobblingEnabled(enabled: Boolean) {
        dataStore.edit { it[SCROBBLING_ENABLED] = enabled }
    }

    suspend fun setSpotifyTokens(accessToken: String, refreshToken: String) {
        dataStore.edit {
            it[SPOTIFY_ACCESS_TOKEN] = accessToken
            it[SPOTIFY_REFRESH_TOKEN] = refreshToken
        }
    }

    fun getSpotifyAccessTokenFlow(): Flow<String?> =
        dataStore.data.map { it[SPOTIFY_ACCESS_TOKEN] }

    fun getLastFmSessionKeyFlow(): Flow<String?> =
        dataStore.data.map { it[LASTFM_SESSION_KEY] }

    suspend fun clearSpotifyTokens() {
        dataStore.edit {
            it.remove(SPOTIFY_ACCESS_TOKEN)
            it.remove(SPOTIFY_REFRESH_TOKEN)
        }
    }

    suspend fun setLastFmSession(sessionKey: String) {
        dataStore.edit { it[LASTFM_SESSION_KEY] = sessionKey }
    }

    suspend fun clearLastFmSession() {
        dataStore.edit { it.remove(LASTFM_SESSION_KEY) }
    }

    suspend fun setSoundCloudToken(token: String) {
        dataStore.edit { it[SOUNDCLOUD_ACCESS_TOKEN] = token }
    }

    fun getSoundCloudTokenFlow(): Flow<String?> =
        dataStore.data.map { it[SOUNDCLOUD_ACCESS_TOKEN] }

    suspend fun getSoundCloudToken(): String? =
        dataStore.data.first()[SOUNDCLOUD_ACCESS_TOKEN]

    suspend fun clearSoundCloudToken() {
        dataStore.edit { it.remove(SOUNDCLOUD_ACCESS_TOKEN) }
    }

    suspend fun getSpotifyAccessToken(): String? =
        dataStore.data.first()[SPOTIFY_ACCESS_TOKEN]

    suspend fun getSpotifyRefreshToken(): String? =
        dataStore.data.first()[SPOTIFY_REFRESH_TOKEN]
}
