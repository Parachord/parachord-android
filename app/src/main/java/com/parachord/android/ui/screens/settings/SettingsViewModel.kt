package com.parachord.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.ListenBrainzApi
import com.parachord.android.data.scanner.MediaScanner
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.QueuePersistence
import com.parachord.android.playback.handlers.MusicKitWebBridge
import com.parachord.android.playback.scrobbler.LibreFmScrobbler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val musicKitBridge: MusicKitWebBridge,
    private val mediaScanner: MediaScanner,
    private val pluginManager: com.parachord.android.plugin.PluginManager,
    private val pluginSyncService: com.parachord.android.plugin.PluginSyncService,
) : ViewModel() {

    /** Loaded .axe plugins — drives the dynamic plugin list in Settings. */
    val loadedPlugins: StateFlow<List<com.parachord.android.plugin.PluginManager.PluginInfo>> =
        pluginManager.plugins

    // ── Dynamic AI Model Lists ───────────────────────────────────────

    data class ModelOption(val value: String, val label: String)

    private val _dynamicModels = MutableStateFlow<List<ModelOption>>(emptyList())
    val dynamicModels: StateFlow<List<ModelOption>> = _dynamicModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    /** Fetch available models from an AI provider's .axe plugin. */
    fun loadModelsForProvider(providerId: String) {
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val apiKey = settingsStore.getAiProviderApiKey(providerId) ?: ""
                val configJson = """{"apiKey":"${apiKey.replace("\"", "\\\"")}"}"""
                val resultJson = pluginManager.listModels(providerId, configJson)
                if (resultJson != null) {
                    val models = parseModelList(resultJson)
                    if (models.isNotEmpty()) {
                        _dynamicModels.value = models
                        _modelsLoading.value = false
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w("SettingsVM", "Failed to load models for $providerId: ${e.message}")
            }
            // Fallback to empty — UI will use hardcoded defaults
            _dynamicModels.value = emptyList()
            _modelsLoading.value = false
        }
    }

    private fun parseModelList(json: String): List<ModelOption> {
        return try {
            val array = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(json)
            if (array is kotlinx.serialization.json.JsonArray) {
                array.mapNotNull { element ->
                    val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val value = obj["value"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@mapNotNull null
                    val label = obj["label"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: value
                    ModelOption(value, label)
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val themeMode: StateFlow<String> = settingsStore.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val scrobblingEnabled: StateFlow<Boolean> = settingsStore.scrobblingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val spotifyConnected: StateFlow<Boolean> = settingsStore.getSpotifyAccessTokenFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether a preferred Spotify device ID is saved. */
    val hasPreferredSpotifyDevice: StateFlow<Boolean> = settingsStore.getPreferredSpotifyDeviceIdFlow()
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

    /** Plugins explicitly disabled by the user. */
    val disabledPlugins: StateFlow<Set<String>> = settingsStore.getDisabledPluginsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPluginEnabled(pluginId, enabled) }
    }

    /** Save an API key for a plugin (stored as {pluginId}_api_key in DataStore). */
    fun savePluginApiKey(pluginId: String, apiKey: String) {
        viewModelScope.launch { settingsStore.setAiProviderApiKey(pluginId, apiKey) }
    }

    fun clearPluginApiKey(pluginId: String) {
        viewModelScope.launch { settingsStore.clearAiProviderApiKey(pluginId) }
    }

    /** Check if a plugin has a stored API key (synchronous for composable use). */
    fun hasPluginApiKey(pluginId: String): Boolean {
        return kotlinx.coroutines.runBlocking {
            settingsStore.getAiProviderApiKey(pluginId) != null
        }
    }

    /** Manually trigger plugin sync from marketplace. */
    fun syncPlugins() {
        viewModelScope.launch {
            try {
                val result = pluginSyncService.sync()
                if (result.hasChanges) {
                    Log.d("SettingsVM", "Plugin sync: ${result.added.size} added, ${result.updated.size} updated")
                } else {
                    Log.d("SettingsVM", "Plugin sync: all up to date")
                }
            } catch (e: Exception) {
                Log.w("SettingsVM", "Plugin sync failed: ${e.message}")
            }
        }
    }

    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setResolverOrder(order: List<String>) {
        viewModelScope.launch { settingsStore.setResolverOrder(order) }
    }

    val chatGptConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("chatgpt")
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val claudeConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("claude")
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val geminiConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("gemini")
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sendListeningHistory: StateFlow<Boolean> = settingsStore.getSendListeningHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setSendListeningHistory(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSendListeningHistory(enabled) }
    }

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

    fun clearPreferredSpotifyDevice() {
        viewModelScope.launch { settingsStore.clearPreferredSpotifyDeviceId() }
    }

    fun disconnectLastFm() {
        viewModelScope.launch { settingsStore.clearLastFmSession() }
    }

    // --- Apple Music ---

    val appleMusicDeveloperToken: StateFlow<String?> = settingsStore.getAppleMusicDeveloperTokenFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val appleMusicStorefront: StateFlow<String?> = settingsStore.getAppleMusicStorefrontFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val appleMusicConfigured: StateFlow<Boolean> = musicKitBridge.configured

    val appleMusicAuthorized: StateFlow<Boolean> = musicKitBridge.authorized

    fun setAppleMusicDeveloperToken(token: String) {
        viewModelScope.launch {
            settingsStore.setAppleMusicDeveloperToken(token)
            musicKitBridge.configure()
        }
    }

    fun setAppleMusicStorefront(storefront: String) {
        viewModelScope.launch { settingsStore.setAppleMusicStorefront(storefront) }
    }

    private val _appleMusicConnecting = MutableStateFlow(false)
    val appleMusicConnecting: StateFlow<Boolean> = _appleMusicConnecting

    fun connectAppleMusic() {
        viewModelScope.launch {
            _appleMusicConnecting.value = true
            try {
                musicKitBridge.authorize()
            } finally {
                _appleMusicConnecting.value = false
            }
        }
    }

    fun disconnectAppleMusic() {
        viewModelScope.launch {
            settingsStore.clearAppleMusicDeveloperToken()
            musicKitBridge.disconnect()
        }
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

    // --- Local Files Scanning ---

    val scanProgress: StateFlow<ScanProgress> = mediaScanner.progress

    fun scanLocalFiles() {
        viewModelScope.launch { mediaScanner.scan() }
    }

    // --- Concert Providers (Ticketmaster / SeatGeek) ---

    val ticketmasterConnected: StateFlow<Boolean> = settingsStore.getTicketmasterApiKeyFlow()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val seatGeekConnected: StateFlow<Boolean> = settingsStore.getSeatGeekClientIdFlow()
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setTicketmasterApiKey(key: String) {
        viewModelScope.launch { settingsStore.setTicketmasterApiKey(key) }
    }

    fun clearTicketmasterApiKey() {
        viewModelScope.launch { settingsStore.clearTicketmasterApiKey() }
    }

    fun setSeatGeekClientId(id: String) {
        viewModelScope.launch { settingsStore.setSeatGeekClientId(id) }
    }

    fun clearSeatGeekClientId() {
        viewModelScope.launch { settingsStore.clearSeatGeekClientId() }
    }

    // --- Concert Location ---

    val concertLocation: StateFlow<SettingsStore.ConcertLocation> =
        settingsStore.getConcertLocationFlow()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                SettingsStore.ConcertLocation(null, null, null, 50),
            )

    fun setConcertLocation(lat: Double, lon: Double, city: String) {
        viewModelScope.launch { settingsStore.setConcertLocation(lat, lon, city) }
    }
}
