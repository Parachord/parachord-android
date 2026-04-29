@file:Suppress("unused")
package com.parachord.android.data.metadata

/**
 * Source-compat typealias. The real implementation moved to
 * `shared/commonMain` so iOS can consume it. Companion functions
 * `coverArtUrl(...)` / `releaseGroupArtUrl(...)` are still reachable
 * via this alias on Kotlin (`MusicBrainzProvider.coverArtUrl(...)`).
 */
typealias MusicBrainzProvider = com.parachord.shared.metadata.MusicBrainzProvider
