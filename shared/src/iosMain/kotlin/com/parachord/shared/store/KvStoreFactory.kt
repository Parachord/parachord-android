package com.parachord.shared.store

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

/**
 * iOS-side factory for [KvStore]. Wraps a named `NSUserDefaults` suite
 * via `multiplatform-settings`' `NSUserDefaultsSettings`.
 *
 * The default suite name `"parachord_kmp_prefs"` matches the Android
 * SharedPreferences file name for symmetry — keeps mental model identical
 * across platforms when reasoning about Phase 9B's KvStore-backed settings.
 *
 * Note: NSUserDefaults suite names with dots (e.g. `"com.parachord.prefs"`)
 * are valid and conventional, but there's no benefit to changing the
 * scheme since this store is per-app anyway. Plain `parachord_kmp_prefs`
 * keeps the file/log identifiers identical to Android.
 */
object KvStoreFactory {

    /** Suite name matching Android's SharedPreferences file name. */
    const val DEFAULT_SUITE_NAME = "parachord_kmp_prefs"

    /**
     * Build a [KvStore] backed by a named `NSUserDefaults` suite.
     *
     * @param suiteName the suite name. Defaults to [DEFAULT_SUITE_NAME];
     *   tests pass a per-test name to isolate state.
     */
    fun create(suiteName: String = DEFAULT_SUITE_NAME): KvStore {
        val defaults = NSUserDefaults(suiteName = suiteName)
        return KvStore(NSUserDefaultsSettings(defaults))
    }
}
