package com.parachord.android.playback

import android.util.Log
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and restores queue state across app restarts.
 *
 * Observes [QueueManager.snapshot] + [PlaybackStateHolder.state] (for currentTrack),
 * debounces changes, and serializes to DataStore as JSON. Only active when the
 * "Remember queue" setting is enabled.
 */
@Singleton
class QueuePersistence @Inject constructor(
    private val settingsStore: SettingsStore,
    private val queueManager: QueueManager,
    private val stateHolder: PlaybackStateHolder,
    private val json: Json,
) {
    companion object {
        private const val TAG = "QueuePersistence"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    /**
     * Start observing queue state and auto-saving when the persist setting is on.
     * Should be called once from PlaybackController.connect().
     */
    @OptIn(FlowPreview::class)
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                queueManager.snapshot,
                stateHolder.state,
                settingsStore.persistQueue,
            ) { snapshot, playbackState, enabled ->
                Triple(snapshot, playbackState, enabled)
            }
                .debounce(500)
                .collectLatest { (snapshot, playbackState, enabled) ->
                    if (!enabled) return@collectLatest
                    save(playbackState.currentTrack, snapshot)
                }
        }
    }

    private suspend fun save(
        currentTrack: com.parachord.android.data.db.entity.TrackEntity?,
        snapshot: QueueSnapshot,
    ) {
        try {
            val state = PersistedQueueState(
                currentTrack = currentTrack?.toSerializable(),
                upNext = snapshot.upNext.map { it.toSerializable() },
                playHistory = queueManager.history.map { it.toSerializable() },
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
                originalOrder = queueManager.savedOriginalOrder?.map { it.toSerializable() },
            )
            val jsonStr = json.encodeToString(PersistedQueueState.serializer(), state)
            settingsStore.setPersistedQueueState(jsonStr)
            Log.d(TAG, "Saved queue: current=${currentTrack?.title}, upNext=${snapshot.upNext.size} tracks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue state", e)
        }
    }

    /**
     * Restore persisted queue if the setting is enabled.
     *
     * @return The saved current track (paused), or null if nothing to restore.
     */
    suspend fun restoreIfEnabled(): com.parachord.android.data.db.entity.TrackEntity? {
        if (!settingsStore.isPersistQueueEnabled()) return null

        val jsonStr = settingsStore.getPersistedQueueState() ?: return null
        return try {
            val state = json.decodeFromString(PersistedQueueState.serializer(), jsonStr)
            if (state.upNext.isEmpty() && state.currentTrack == null) return null

            queueManager.restoreState(
                restoredUpNext = state.upNext.map { it.toTrackEntity() },
                restoredHistory = state.playHistory.map { it.toTrackEntity() },
                restoredOriginalOrder = state.originalOrder?.map { it.toTrackEntity() },
                context = state.playbackContext,
                shuffle = state.shuffleEnabled,
            )

            val track = state.currentTrack?.toTrackEntity()
            Log.d(TAG, "Restored queue: current=${track?.title}, upNext=${state.upNext.size} tracks")
            track
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore queue state", e)
            null
        }
    }

    /** Clear any persisted queue data. */
    suspend fun clearPersistedQueue() {
        settingsStore.clearPersistedQueueState()
        Log.d(TAG, "Cleared persisted queue")
    }
}
