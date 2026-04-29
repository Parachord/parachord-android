package com.parachord.android.data.repository

/**
 * Bridge typealias — the implementation lives in
 * `com.parachord.shared.repository.LibraryRepository` so it can be shared
 * with iOS. The Android-only `MbidEnrichmentService` is forwarded via
 * two non-suspend fire-and-forget lambdas wired in `AndroidModule`.
 */
typealias LibraryRepository = com.parachord.shared.repository.LibraryRepository
