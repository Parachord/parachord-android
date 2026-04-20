package com.parachord.android.playlist

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Schedules recurring polls of hosted XSPF playlists. Mirrors the dual-track
 * pattern used by [com.parachord.android.sync.SyncScheduler]:
 *   - Foreground timer fires every 5 minutes while the app is running.
 *   - WorkManager periodic job (15-minute floor on Android) catches updates
 *     when the app is backgrounded.
 *
 * Both paths delegate to [HostedPlaylistPoller.pollAll].
 */
class HostedPlaylistScheduler constructor(
    private val context: Context,
    private val poller: HostedPlaylistPoller,
) {
    companion object {
        private const val TAG = "HostedPlaylistScheduler"
        private const val IN_APP_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null

    fun startInAppTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            // One-shot repair for pre-cache-bust-fix mosaics. Idempotent —
            // only touches rows whose stored artworkUrl lacks `?v=`.
            try {
                poller.repairHostedArt()
            } catch (e: Exception) {
                Log.w(TAG, "repairHostedArt threw: ${e.message}")
            }
            while (isActive) {
                try {
                    poller.pollAll()
                } catch (e: Exception) {
                    Log.w(TAG, "pollAll threw: ${e.message}")
                }
                delay(IN_APP_INTERVAL_MS)
            }
        }
        Log.d(TAG, "In-app hosted-playlist timer started (${IN_APP_INTERVAL_MS / 60000}min interval)")
    }

    fun stopInAppTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun enableWorkManagerPolling() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 15 min is Android's floor for periodic work.
        val request = PeriodicWorkRequestBuilder<HostedPlaylistWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HostedPlaylistWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "WorkManager hosted-playlist polling enabled (15min interval)")
    }

    fun disableWorkManagerPolling() {
        WorkManager.getInstance(context).cancelUniqueWork(HostedPlaylistWorker.WORK_NAME)
    }
}
