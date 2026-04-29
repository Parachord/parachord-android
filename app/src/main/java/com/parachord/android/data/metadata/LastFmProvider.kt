@file:Suppress("unused")
package com.parachord.android.data.metadata

/**
 * Source-compat typealias. The real implementation moved to
 * `shared/commonMain` so iOS can consume it. The Android Koin module
 * sources `apiKey` from `BuildConfig.LASTFM_API_KEY` via [com.parachord.shared.config.AppConfig].
 */
typealias LastFmProvider = com.parachord.shared.metadata.LastFmProvider
