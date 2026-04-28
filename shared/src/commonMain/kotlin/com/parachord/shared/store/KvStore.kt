package com.parachord.shared.store

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanFlow
import com.russhwolf.settings.coroutines.getIntFlow
import com.russhwolf.settings.coroutines.getLongFlow
import com.russhwolf.settings.coroutines.getStringFlow
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import kotlinx.coroutines.flow.Flow

/**
 * KMP-friendly key-value store. Phase 9B foundation.
 *
 * Wraps `multiplatform-settings`' [ObservableSettings] with a typed,
 * Flow-based API that mirrors how the existing DataStore-backed
 * [com.parachord.android.data.store.SettingsStore] is consumed today —
 * `Flow<T>` for reactive reads, suspend setters that block the calling
 * coroutine until the write commits.
 *
 * Backends:
 * - **Android**: `SharedPreferencesSettings` (the multiplatform-settings
 *   Android target). One named `SharedPreferences` file per `KvStore`
 *   instance — production uses `"parachord_kmp_prefs"`.
 * - **iOS**: `NSUserDefaultsSettings` (the multiplatform-settings Darwin
 *   target). Uses the app's standard suite or a named suite name when
 *   provided.
 *
 * Phase 9B sequencing:
 * - Stage 1 (this commit): the abstraction + Android factory + Koin
 *   registration. No consumers yet — `SettingsStore` keeps using
 *   DataStore.
 * - Stage 2: convert `SettingsStore` keys to `KvStore` calls one
 *   surface at a time (sync settings first, since `SyncSettingsProvider`
 *   is the highest-leverage iOS-blocker). One-time migration reads each
 *   key from DataStore and writes through `KvStore` on first launch.
 * - Stage 3: move `SettingsStore` to `shared/commonMain` once all
 *   reads/writes go through `KvStore` and DataStore is gone.
 *
 * **Why a wrapper instead of using `ObservableSettings` directly:**
 * - Consumers want `Flow<T>` for reads — the multiplatform-settings
 *   Flow extensions are correct but live in the `coroutines` artifact
 *   under `com.russhwolf.settings.coroutines.*`. Wrapping keeps
 *   consumer imports stable.
 * - The Phase 9B Stage 2 migration needs a fixed seam to swap from
 *   DataStore-backed reads to KvStore-backed reads without touching
 *   each call site twice.
 * - Tests can substitute an in-memory `MapSettings`-backed `KvStore`
 *   without an Android context.
 */
@OptIn(ExperimentalSettingsApi::class)
class KvStore(private val backing: ObservableSettings) {

    // ── String ──────────────────────────────────────────────────────

    fun getString(key: String, default: String = ""): String =
        backing.getString(key, default)

    fun getStringOrNull(key: String): String? = backing.getStringOrNull(key)

    suspend fun setString(key: String, value: String) {
        backing.putString(key, value)
    }

    fun observeString(key: String, default: String = ""): Flow<String> =
        backing.getStringFlow(key, default)

    fun observeStringOrNull(key: String): Flow<String?> =
        backing.getStringOrNullFlow(key)

    // ── Int ─────────────────────────────────────────────────────────

    fun getInt(key: String, default: Int = 0): Int = backing.getInt(key, default)

    suspend fun setInt(key: String, value: Int) {
        backing.putInt(key, value)
    }

    fun observeInt(key: String, default: Int = 0): Flow<Int> =
        backing.getIntFlow(key, default)

    // ── Long ────────────────────────────────────────────────────────

    fun getLong(key: String, default: Long = 0L): Long =
        backing.getLong(key, default)

    suspend fun setLong(key: String, value: Long) {
        backing.putLong(key, value)
    }

    fun observeLong(key: String, default: Long = 0L): Flow<Long> =
        backing.getLongFlow(key, default)

    // ── Boolean ─────────────────────────────────────────────────────

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        backing.getBoolean(key, default)

    suspend fun setBoolean(key: String, value: Boolean) {
        backing.putBoolean(key, value)
    }

    fun observeBoolean(key: String, default: Boolean = false): Flow<Boolean> =
        backing.getBooleanFlow(key, default)

    // ── Set<String> (encoded as comma-separated string for portability) ─

    /**
     * Read a string-set serialized as a comma-separated value.
     * Many existing `SettingsStore` keys (e.g. `enabled_sync_providers`,
     * `sync_collections_*`) already use this convention; preserving it
     * means the Phase 9B Stage 2 migration only changes the *backing*
     * store, not the wire format. Empty / missing → empty set.
     */
    fun getStringSetCsv(key: String): Set<String> {
        val raw = backing.getStringOrNull(key) ?: return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    suspend fun setStringSetCsv(key: String, value: Set<String>) {
        backing.putString(key, value.joinToString(","))
    }

    // ── Generic ─────────────────────────────────────────────────────

    fun contains(key: String): Boolean = backing.hasKey(key)

    suspend fun remove(key: String) {
        backing.remove(key)
    }

    /** Wipe all keys in this KvStore. Equivalent to DataStore's `clear()`. */
    suspend fun clear() {
        backing.clear()
    }

    /**
     * Snapshot of every (key, type) currently stored. Used by the Phase 9B
     * Stage 2 migration to verify a successful DataStore → KvStore copy.
     */
    val keys: Set<String> get() = backing.keys
}
