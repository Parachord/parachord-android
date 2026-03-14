package com.parachord.android.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.sync.SpotifySyncProvider
import com.parachord.android.sync.SyncEngine
import com.parachord.android.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val settingsStore: SettingsStore,
    private val spotifyProvider: SpotifySyncProvider,
) : ViewModel() {

    enum class SetupStep { OPTIONS, PLAYLISTS, SYNCING, COMPLETE }

    private val _currentStep = MutableStateFlow(SetupStep.OPTIONS)
    val currentStep: StateFlow<SetupStep> = _currentStep

    private val _syncTracks = MutableStateFlow(true)
    val syncTracks: StateFlow<Boolean> = _syncTracks

    private val _syncAlbums = MutableStateFlow(true)
    val syncAlbums: StateFlow<Boolean> = _syncAlbums

    private val _syncArtists = MutableStateFlow(true)
    val syncArtists: StateFlow<Boolean> = _syncArtists

    private val _syncPlaylists = MutableStateFlow(true)
    val syncPlaylists: StateFlow<Boolean> = _syncPlaylists

    fun setSyncTracks(v: Boolean) { _syncTracks.value = v }
    fun setSyncAlbums(v: Boolean) { _syncAlbums.value = v }
    fun setSyncArtists(v: Boolean) { _syncArtists.value = v }
    fun setSyncPlaylists(v: Boolean) { _syncPlaylists.value = v }

    private val _availablePlaylists = MutableStateFlow<List<SpotifySyncProvider.SyncedPlaylist>>(emptyList())
    val availablePlaylists: StateFlow<List<SpotifySyncProvider.SyncedPlaylist>> = _availablePlaylists

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading

    private val _playlistsError = MutableStateFlow<String?>(null)
    val playlistsError: StateFlow<String?> = _playlistsError

    private val _selectedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPlaylistIds: StateFlow<Set<String>> = _selectedPlaylistIds

    private val _playlistFilter = MutableStateFlow("all")
    val playlistFilter: StateFlow<String> = _playlistFilter

    fun setPlaylistFilter(filter: String) { _playlistFilter.value = filter }
    fun togglePlaylistSelection(spotifyId: String) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value.let {
            if (spotifyId in it) it - spotifyId else it + spotifyId
        }
    }
    fun selectAllPlaylists(spotifyIds: List<String>) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value + spotifyIds
    }
    fun deselectAllPlaylists(spotifyIds: List<String>) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value - spotifyIds.toSet()
    }

    private val _syncProgress = MutableStateFlow(SyncEngine.SyncProgress(SyncEngine.SyncPhase.TRACKS))
    val syncProgress: StateFlow<SyncEngine.SyncProgress> = _syncProgress

    private val _syncResult = MutableStateFlow<SyncEngine.FullSyncResult?>(null)
    val syncResult: StateFlow<SyncEngine.FullSyncResult?> = _syncResult

    val syncEnabled = settingsStore.syncEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lastSyncAt = settingsStore.lastSyncAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun proceedFromOptions() {
        if (_syncPlaylists.value) {
            // Transition immediately — show skeleton loaders while fetching
            _playlistsLoading.value = true
            _playlistsError.value = null
            _availablePlaylists.value = emptyList()
            _currentStep.value = SetupStep.PLAYLISTS
            viewModelScope.launch {
                try {
                    val playlists = spotifyProvider.fetchPlaylists()
                    _availablePlaylists.value = playlists
                    _selectedPlaylistIds.value = playlists.map { it.spotifyId }.toSet()
                } catch (e: Exception) {
                    _playlistsError.value = e.message ?: "Failed to load playlists"
                } finally {
                    _playlistsLoading.value = false
                }
            }
        } else {
            startSync()
        }
    }

    fun goBackToOptions() {
        _currentStep.value = SetupStep.OPTIONS
        _playlistsError.value = null
    }

    fun startSync() {
        _currentStep.value = SetupStep.SYNCING
        viewModelScope.launch {
            settingsStore.saveSyncSettings(SettingsStore.SyncSettings(
                enabled = true,
                provider = "spotify",
                syncTracks = _syncTracks.value,
                syncAlbums = _syncAlbums.value,
                syncArtists = _syncArtists.value,
                syncPlaylists = _syncPlaylists.value,
                selectedPlaylistIds = _selectedPlaylistIds.value,
                pushLocalPlaylists = true,
            ))

            syncScheduler.startInAppTimer()
            syncScheduler.enableWorkManagerSync()

            val result = syncEngine.syncAll { progress ->
                _syncProgress.value = progress
            }
            _syncResult.value = result
            _currentStep.value = SetupStep.COMPLETE
        }
    }

    fun syncNow() {
        _currentStep.value = SetupStep.SYNCING
        viewModelScope.launch {
            val result = syncEngine.syncAll { progress ->
                _syncProgress.value = progress
            }
            _syncResult.value = result
            _currentStep.value = SetupStep.COMPLETE
        }
    }

    fun stopSyncing(removeItems: Boolean) {
        viewModelScope.launch {
            syncEngine.stopSyncing(removeItems)
            syncScheduler.stopInAppTimer()
            syncScheduler.disableWorkManagerSync()
        }
    }

    fun resetSetup() {
        _currentStep.value = SetupStep.OPTIONS
        _syncResult.value = null
    }
}
