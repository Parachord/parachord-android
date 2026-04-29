@file:Suppress("unused")
package com.parachord.android.data.repository

/**
 * Source-compat typealiases. The real implementation moved to
 * `shared/commonMain` so iOS can consume the same ListenBrainz Weekly
 * Jams + Weekly Exploration repository. The class itself is unchanged
 * apart from swapping `System.currentTimeMillis()` for the shared
 * [com.parachord.shared.platform.currentTimeMillis] expect/actual.
 */
typealias WeeklyPlaylistsRepository = com.parachord.shared.repository.WeeklyPlaylistsRepository
typealias WeeklyPlaylistsResult = com.parachord.shared.repository.WeeklyPlaylistsResult
typealias WeeklyPlaylistEntry = com.parachord.shared.repository.WeeklyPlaylistEntry
