@file:Suppress("unused")
package com.parachord.android.data.repository

/**
 * Source-compat typealias. The real implementation moved to
 * `shared/commonMain` so iOS can consume the same Last.fm + ListenBrainz
 * history pipeline. The Last.fm API key is now passed as a constructor
 * parameter (`lastFmApiKey`) — Android sources it from
 * `BuildConfig.LASTFM_API_KEY` via the Koin module; iOS will source
 * the same value from `AppConfig` once the iOS DI module lights up.
 *
 * The `synchronized(map)` blocks that guarded the artwork/image enrichment
 * caches were swapped to `kotlinx.coroutines.sync.Mutex` since
 * `synchronized` is JVM-only.
 */
typealias HistoryRepository = com.parachord.shared.repository.HistoryRepository
