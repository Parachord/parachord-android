@file:Suppress("unused")
package com.parachord.android.data.repository

/**
 * Source-compat typealias. The real implementation moved to
 * `shared/commonMain` so iOS can consume the same Last.fm + ListenBrainz
 * friend management.
 *
 * Constructor changes:
 * - `lastFmApiKey: String` is now a constructor parameter (previously
 *   read from `BuildConfig.LASTFM_API_KEY`). The Koin module sources it
 *   from BuildConfig.
 * - `java.util.UUID.randomUUID()` → shared `randomUUID()` expect/actual
 * - `System.currentTimeMillis()` → shared `currentTimeMillis()` expect/actual
 * - `Dispatchers.IO` → `Dispatchers.Default` (matches every other shared
 *   DAO call site; SQLDelight wraps queries in `Dispatchers.Default`
 *   already so this is consistent).
 * - `synchronized(map) { ... }` → `kotlinx.coroutines.sync.Mutex.withLock { ... }`
 *   (JVM-only `synchronized` doesn't exist in commonMain).
 *
 * `ParsedFriend` is a nested class — accessible via this alias as
 * `FriendsRepository.ParsedFriend` from existing call sites.
 */
typealias FriendsRepository = com.parachord.shared.repository.FriendsRepository
