package com.parachord.android.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LibrarySyncWorker"
        const val WORK_NAME = "library_sync"
        private const val MIN_SYNC_GAP_MS = 10 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) {
            Log.d(TAG, "Sync not enabled, skipping")
            return Result.success()
        }

        val lastSync = settingsStore.lastSyncAtFlow.first()
        if (System.currentTimeMillis() - lastSync < MIN_SYNC_GAP_MS) {
            Log.d(TAG, "Skipping — last sync was recent")
            return Result.success()
        }

        return try {
            oAuthManager.refreshSpotifyToken()

            val result = syncEngine.syncAll()
            if (result.success) {
                Log.d(TAG, "Background sync complete: $result")
                Result.success()
            } else {
                Log.w(TAG, "Sync reported failure: ${result.error}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
