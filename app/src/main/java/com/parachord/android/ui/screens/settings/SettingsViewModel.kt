package com.parachord.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
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
}
