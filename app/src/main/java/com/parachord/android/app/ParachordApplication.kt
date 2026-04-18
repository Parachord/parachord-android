package com.parachord.android.app

import android.app.Application
import com.parachord.android.di.androidModule
import com.parachord.android.playlist.HostedPlaylistScheduler
import com.parachord.shared.di.sharedModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ParachordApplication : Application() {

    private val hostedPlaylistScheduler: HostedPlaylistScheduler by inject()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(CurrentActivityHolder)
        startKoin {
            androidContext(this@ParachordApplication)
            modules(sharedModule, androidModule)
        }
        // Hosted XSPF polling is orthogonal to Spotify sync — it should run
        // whenever the app has hosted playlists, regardless of sync settings.
        // The scheduler short-circuits when there are no hosted rows.
        hostedPlaylistScheduler.startInAppTimer()
        hostedPlaylistScheduler.enableWorkManagerPolling()
    }
}
