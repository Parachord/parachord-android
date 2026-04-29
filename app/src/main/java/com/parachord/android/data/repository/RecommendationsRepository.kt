package com.parachord.android.data.repository

/**
 * Bridge typealias — the implementation lives in
 * `com.parachord.shared.repository.RecommendationsRepository` so it can be
 * shared with iOS. File I/O (cache file at `<filesDir>/recommendations_cache.json`)
 * is wired in via suspend lambdas in `AndroidModule`.
 */
typealias RecommendationsRepository = com.parachord.shared.repository.RecommendationsRepository
