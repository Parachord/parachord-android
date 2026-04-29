@file:Suppress("unused")
package com.parachord.android.data.repository

import com.parachord.shared.repository.FreshDrop
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Source-compat typealiases. Mirrors the `ConcertsRepository` move pattern:
 * - `Context + java.io.File` cache + rotation I/O became four suspend lambdas
 *   in the shared constructor (`cacheRead/Write`, `rotationRead/Write`).
 * - `MbidEnrichmentService` (Android-only — has its own `Context + File`
 *   issue) became two suspend lambdas (`mbidLookupCached`,
 *   `mbidLookupViaMapper`). The Android Koin binding forwards them to the
 *   live service; iOS will wire equivalents later.
 * - `java.time.LocalDate.minusMonths(6)` → `kotlinx.datetime.LocalDate.minus(6, DateTimeUnit.MONTH)`.
 *   `kotlinx-datetime`'s `LocalDate.toString()` already emits the
 *   ISO-8601 format the previous `DateTimeFormatter.ISO_LOCAL_DATE`
 *   produced, so the `cutoffStr` comparison is unchanged.
 *
 * `displayDate` / `isUpcoming` stayed Android-only because `MMM d, yyyy`
 * formatting is locale-dependent. The unit test file
 * (`FreshDropsRepositoryTest`) is unchanged — it still asserts against
 * `drop.displayDate` and `drop.isUpcoming`, which now resolve to these
 * extensions since the test sits in the same package.
 */
typealias FreshDropsRepository = com.parachord.shared.repository.FreshDropsRepository
typealias FreshDrop = com.parachord.shared.repository.FreshDrop

// ── Android-only display formatters (extension properties) ──────────────

/** True if this release date is in the future. */
val FreshDrop.isUpcoming: Boolean
    get() {
        val d = date ?: return false
        return try {
            LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE).isAfter(LocalDate.now())
        } catch (_: Exception) { false }
    }

/** Formatted display date. "Coming Mar 22, 2026" for future releases. */
val FreshDrop.displayDate: String
    get() {
        val d = date ?: return ""
        return try {
            val parsed = LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE)
            val formatted = parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            if (isUpcoming) "Coming $formatted" else formatted
        } catch (_: Exception) { d }
    }
