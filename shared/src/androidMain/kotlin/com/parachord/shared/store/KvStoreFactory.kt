package com.parachord.shared.store

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Android-side factory for [KvStore]. Wraps a named `SharedPreferences` file
 * via `multiplatform-settings`' `SharedPreferencesSettings`.
 *
 * The default file name `"parachord_kmp_prefs"` is intentionally distinct
 * from the DataStore preferences file (`"parachord_settings"`) so the
 * Phase 9B Stage 2 migration can run safely — read from the old DataStore,
 * write through `KvStore` into the new file. Each user gets exactly one
 * migration pass; subsequent launches read the new file directly.
 *
 * iOS will get its own `KvStoreFactory` in `iosMain` backed by
 * `NSUserDefaultsSettings`.
 */
object KvStoreFactory {

    /** The standard shared-preferences file name for production. */
    const val DEFAULT_FILE_NAME = "parachord_kmp_prefs"

    /**
     * Build a [KvStore] backed by Android `SharedPreferences`.
     *
     * @param context any `Context` — we use `applicationContext` internally.
     * @param fileName the SharedPreferences file name. Defaults to
     *   [DEFAULT_FILE_NAME]; tests pass a per-test name to isolate state.
     */
    fun create(context: Context, fileName: String = DEFAULT_FILE_NAME): KvStore {
        val prefs = context.applicationContext.getSharedPreferences(
            fileName,
            Context.MODE_PRIVATE,
        )
        return KvStore(SharedPreferencesSettings(prefs))
    }
}
