package com.parachord.android.data.api

import com.parachord.android.BuildConfig
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Apple Music's required auth headers on every API call:
 *
 * - `Authorization: Bearer {dev-token}` — the JWT signed with the
 *   developer team's private key, baked into the APK at build time
 *   via `BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN` (configured in
 *   `local.properties`).
 * - `Music-User-Token: {mut}` — the per-user Music User Token obtained
 *   through `MusicKitWebBridge.authorize()` and persisted in
 *   [SettingsStore.getAppleMusicUserToken]. Omitted from the request
 *   when blank; Apple returns 401 in that case and the upstream caller
 *   raises [com.parachord.android.sync.AppleMusicReauthRequiredException].
 *
 * The MUT lookup uses [runBlocking] because OkHttp's [Interceptor.intercept]
 * isn't a suspend function. The lookup is a single DataStore read; on the
 * IO-dispatched HTTP call it adds negligible overhead.
 */
class AppleMusicAuthInterceptor(
    private val settingsStore: SettingsStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val devToken = BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN
        val mut = runBlocking { settingsStore.getAppleMusicUserToken() }
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $devToken")
            .apply { if (!mut.isNullOrBlank()) addHeader("Music-User-Token", mut) }
            .build()
        return chain.proceed(request)
    }
}
