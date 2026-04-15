package com.parachord.android.app

import android.app.Application
import com.parachord.android.di.androidModule
import com.parachord.shared.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ParachordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ParachordApplication)
            modules(sharedModule, androidModule)
        }
    }
}
