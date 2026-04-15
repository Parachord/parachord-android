package com.parachord.android.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the currently-resumed Activity so non-activity components (e.g.
 * OAuthManager, injected at Application scope) can launch Custom Tabs from
 * an Activity context. Launching from an Activity context allows the tab to
 * run in the same task as the Activity, so the Chrome Custom Tab is popped
 * off the back stack when MainActivity returns after the OAuth redirect
 * rather than leaving a stray Chrome task behind.
 */
object CurrentActivityHolder : Application.ActivityLifecycleCallbacks {
    private var ref: WeakReference<Activity>? = null

    val current: Activity? get() = ref?.get()

    override fun onActivityResumed(activity: Activity) {
        ref = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }
}
