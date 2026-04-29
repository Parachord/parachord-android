@file:Suppress("unused")
package com.parachord.android.data.repository

/**
 * Source-compat typealias. The real implementation moved to
 * `shared/commonMain` in the OkHttp → Ktor migration.
 *
 * - Apple Music marketing-tools RSS feeds (`rss.marketingtools.apple.com`)
 *   moved to `AppleMusicClient.mostPlayedAlbums/Songs`. The earlier
 *   `okhttp3.Request` + `JSONObject` parsing went away — both endpoints
 *   now flow through Ktor + `kotlinx.serialization`.
 * - The Last.fm chart paths already used the shared `LastFmClient` so
 *   no API change there.
 * - `lastFmApiKey` is now a constructor parameter (was `BuildConfig.LASTFM_API_KEY`
 *   referenced inline). The Koin module sources it from BuildConfig.
 * - The two in-memory caches are now `Mutex`-guarded to be cross-platform
 *   safe (previously they relied on `withContext(Dispatchers.IO)`'s
 *   single-thread serialization, which is fine on JVM but not portable).
 *
 * Closes the OkHttp+JSONObject footprint in the data/repository layer
 * for the chart pipeline.
 */
typealias ChartsRepository = com.parachord.shared.repository.ChartsRepository
