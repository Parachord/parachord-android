@file:Suppress("unused")
package com.parachord.android.data.repository

import com.parachord.shared.repository.ConcertEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Source-compat typealiases. The real implementation moved to
 * `shared/commonMain` in the file-system / kotlinx-datetime cleanup.
 *
 * - `Context + java.io.File` cache I/O became two suspend lambdas
 *   passed into the constructor (`cacheRead` / `cacheWrite`). Android
 *   wires `Context.filesDir`-backed reads/writes; iOS will wire
 *   `NSFileManager` once that target lights up. Avoided pulling in
 *   `okio` for one tiny JSON cache file.
 * - `java.time` → `kotlinx-datetime`. The two API call sites that
 *   format `LocalDateTime.now()` for Ticketmaster's `startDateTime` /
 *   SeatGeek's `datetime_gte` got hand-rolled `pad2`/`pad4` zero-padding
 *   formatters since `kotlinx-datetime` doesn't ship a portable
 *   `DateTimeFormatter` analogue.
 * - `java.util.Locale("", country).displayCountry` for ISO 3166 alpha-2
 *   → English country names doesn't have a clean KMP equivalent (the
 *   shared module would need to ship a 250-row code→name table).
 *   Solved by moving `displayDate`, `displayTime`, `locationString` to
 *   Android-only extension properties in this file. Call sites still
 *   write `event.displayDate` etc. — Kotlin extension properties on a
 *   shared data class are visually indistinguishable from members at
 *   the call site, so no UI/test changes were needed.
 *
 * `isUpcoming` stayed in shared (used by `mergeAndDedupe` to filter past
 * events) and was re-implemented with `kotlinx.datetime.LocalDate`.
 */
typealias ConcertsRepository = com.parachord.shared.repository.ConcertsRepository
typealias ConcertEvent = com.parachord.shared.repository.ConcertEvent
typealias TicketSource = com.parachord.shared.repository.TicketSource
typealias ConcertArtist = com.parachord.shared.repository.ConcertArtist

// ── Android-only display formatters (extension properties) ──────────────

/** Formatted display date (e.g. "Sat, Mar 22, 2026"). */
val ConcertEvent.displayDate: String
    get() {
        val d = date ?: return ""
        return try {
            val parsed = LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE)
            parsed.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
        } catch (_: Exception) { d }
    }

/** Formatted display time (e.g. "8:00 PM"). */
val ConcertEvent.displayTime: String
    get() {
        val t = time ?: return ""
        return try {
            val parts = t.split(":")
            val hour = parts[0].toInt()
            val min = parts[1]
            val ampm = if (hour >= 12) "PM" else "AM"
            val h12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            "$h12:$min $ampm"
        } catch (_: Exception) { t }
    }

/** Location string like "New York, NY, United States" or "London, United Kingdom". */
val ConcertEvent.locationString: String
    get() = buildString {
        val base = displayLocation ?: buildString {
            city?.let { append(it) }
            state?.let { if (isNotEmpty()) append(", "); append(it) }
        }
        append(base)
        val countryName = country?.let { resolveCountryName(it) }
        if (countryName != null && !toString().contains(countryName, ignoreCase = true)) {
            if (isNotEmpty()) append(", ")
            append(countryName)
        } else if (countryName != null && isEmpty()) {
            append(countryName)
        }
    }

/** Convert a country code (e.g. "US") or name to a full English display name. */
private fun resolveCountryName(country: String): String {
    if (country.length == 2) {
        return try {
            java.util.Locale("", country).displayCountry
        } catch (_: Exception) { country }
    }
    return country
}
