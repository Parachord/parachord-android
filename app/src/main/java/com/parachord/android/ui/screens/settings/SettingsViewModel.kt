package com.parachord.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.ListenBrainzApi
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.QueuePersistence
import com.parachord.android.playback.scrobbler.LibreFmScrobbler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
    private val queuePersistence: QueuePersistence,
    private val libreFmScrobbler: LibreFmScrobbler,
    private val listenBrainzApi: ListenBrainzApi,
) : ViewModel() {

    val themeMode: StateFlow<String> = settingsStore.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val scrobblingEnabled: StateFlow<Boolean> = settingsStore.scrobblingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val spotifyConnected: StateFlow<Boolean> = settingsStore.getSpotifyAccessTokenFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lastFmConnected: StateFlow<Boolean> = settingsStore.getLastFmSessionKeyFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val listenBrainzConnected: StateFlow<Boolean> = settingsStore.getListenBrainzTokenFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val libreFmConnected: StateFlow<Boolean> = settingsStore.getLibreFmSessionKeyFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val soundCloudConnected: StateFlow<Boolean> = settingsStore.getSoundCloudTokenFlow()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val persistQueue: StateFlow<Boolean> = settingsStore.persistQueue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Set of disabled metadata provider names (e.g. "discogs", "wikipedia"). */
    val disabledMetaProviders: StateFlow<Set<String>> = settingsStore.getDisabledMetaProvidersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val chatGptConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("chatgpt")
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val claudeConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("claude")
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val geminiConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("gemini")
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Tracks Libre.fm auth error state for UI feedback. */
    val libreFmAuthError: MutableStateFlow<String?> = MutableStateFlow(null)

    fun setPersistQueue(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setPersistQueue(enabled)
            if (!enabled) queuePersistence.clearPersistedQueue()
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    fun setScrobbling(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setScrobblingEnabled(enabled) }
    }

    fun connectSpotify(clientId: String) {
        oAuthManager.launchSpotifyAuth(clientId)
    }

    fun connectLastFm(apiKey: String) {
        oAuthManager.launchLastFmAuth(apiKey)
    }

    fun disconnectSpotify() {
        viewModelScope.launch { settingsStore.clearSpotifyTokens() }
    }

    fun disconnectLastFm() {
        viewModelScope.launch { settingsStore.clearLastFmSession() }
    }

    // --- SoundCloud ---

    /** Whether the user has saved SoundCloud client credentials (BYOK). */
    val soundCloudCredentialsSaved: StateFlow<Boolean> = settingsStore.getSoundCloudClientIdFlow()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun saveSoundCloudCredentials(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            settingsStore.setSoundCloudCredentials(clientId, clientSecret)
        }
    }

    fun connectSoundCloud() {
        viewModelScope.launch {
            oAuthManager.launchSoundCloudAuth()
        }
    }

    fun disconnectSoundCloud() {
        viewModelScope.launch { settingsStore.clearSoundCloudToken() }
    }

    fun clearSoundCloudCredentials() {
        viewModelScope.launch { settingsStore.clearSoundCloudCredentials() }
    }

    // --- ListenBrainz ---

    /** Error state for LB token validation feedback. */
    val listenBrainzAuthError: MutableStateFlow<String?> = MutableStateFlow(null)

    fun setListenBrainzToken(token: String) {
        listenBrainzAuthError.value = null
        viewModelScope.launch {
            // Validate the token and extract the username (mirrors desktop's validateToken)
            val username = listenBrainzApi.validateToken(token)
            if (username != null) {
                settingsStore.setListenBrainzToken(token)
                settingsStore.setListenBrainzUsername(username)
                Log.d("SettingsVM", "ListenBrainz connected as: $username")
            } else {
                listenBrainzAuthError.value = "Invalid token. Check your ListenBrainz user token."
            }
        }
    }

    fun disconnectListenBrainz() {
        viewModelScope.launch {
            settingsStore.clearListenBrainzToken()
            settingsStore.clearListenBrainzUsername()
        }
    }

    // --- Libre.fm ---

    fun connectLibreFm(username: String, password: String) {
        libreFmAuthError.value = null
        viewModelScope.launch {
            val result = libreFmScrobbler.authenticate(username, password)
            if (result == null) {
                libreFmAuthError.value = "Authentication failed. Check your credentials."
            }
        }
    }

    fun disconnectLibreFm() {
        viewModelScope.launch { settingsStore.clearLibreFmSession() }
    }

    // --- Meta provider enable/disable ---

    fun setMetaProviderEnabled(providerName: String, enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMetaProviderEnabled(providerName, enabled) }
    }

    fun saveAiProviderConfig(providerId: String, apiKey: String, model: String) {
        viewModelScope.launch {
            settingsStore.setAiProviderApiKey(providerId, apiKey)
            if (model.isNotBlank()) settingsStore.setAiProviderModel(providerId, model)
        }
    }

    fun saveAiModel(providerId: String, model: String) {
        viewModelScope.launch { settingsStore.setAiProviderModel(providerId, model) }
    }

    /** Get the currently saved model for a provider (for UI initialization). */
    fun getAiModel(providerId: String): StateFlow<String> =
        settingsStore.getAiProviderModelFlow(providerId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun clearAiProvider(providerId: String) {
        viewModelScope.launch { settingsStore.clearAiProviderApiKey(providerId) }
    }
}
