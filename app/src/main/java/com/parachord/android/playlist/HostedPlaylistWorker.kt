package com.parachord.android.playlist

import android.content.Context
import com.parachord.shared.platform.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager wrapper around [HostedPlaylistPoller.pollAll]. Runs on a
 * 15-minute periodic cadence (Android's floor for periodic work) scheduled
 * by [HostedPlaylistScheduler.enableWorkManagerPolling].
 */
class HostedPlaylistWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val poller: HostedPlaylistPoller by inject()

    companion object {
        private const val TAG = "HostedPlaylistWorker"
        const val WORK_NAME = "hosted_playlist_poll"
    }

    override suspend fun doWork(): Result = try {
        poller.pollAll()
        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "Hosted playlist polling failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
