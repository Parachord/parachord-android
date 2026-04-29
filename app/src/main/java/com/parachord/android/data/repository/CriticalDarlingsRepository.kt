@file:Suppress("unused")
package com.parachord.android.data.repository

/**
 * Source-compat typealiases. The real implementation moved to
 * `shared/commonMain` along with:
 *  - **OkHttp → shared Ktor `HttpClient`** for the RSS fetch.
 *  - **`XmlPullParser` → regex-based item extraction.** RSS structure
 *    is simple (`<item><title/><link/><description/></item>`) and
 *    stable enough that pulling in a KMP XML library for one feed isn't
 *    worth the dep weight. CDATA sections handled inline.
 *  - **`java.util.Date pubDate` field dropped.** Never displayed by the
 *    UI; the `KSerializer` surrogate that bridged `Date` ↔ `pubDateMs`
 *    went with it. Existing on-disk caches with the `pubDateMs` key are
 *    tolerated via `ignoreUnknownKeys = true`.
 *  - **`Context + java.io.File` cache** → `cacheRead`/`cacheWrite`
 *    suspend lambdas (same pattern as Concerts/FreshDrops).
 *
 * The unit test (`CriticalDarlingsRepositoryTest`) replicates parsing
 * logic inline rather than calling the repository, so it's unaffected
 * by this move.
 */
typealias CriticalDarlingsRepository = com.parachord.shared.repository.CriticalDarlingsRepository
typealias CriticsPickAlbum = com.parachord.shared.repository.CriticsPickAlbum
