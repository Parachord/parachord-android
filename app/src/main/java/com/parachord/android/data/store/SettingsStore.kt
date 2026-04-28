package com.parachord.android.data.store

/**
 * Phase 9B Stage 3: [SettingsStore] now lives in
 * `com.parachord.shared.settings.SettingsStore` (KMP-friendly,
 * KvStore + SecureTokenStore backed). This file-scoped typealias
 * preserves source compatibility for code that still imports
 * `com.parachord.android.data.store.SettingsStore`.
 *
 * The Android-specific DataStore→KvStore migration runs once on first
 * launch — see [AndroidDataStoreMigration]. Subsequent launches read
 * exclusively through KvStore (`parachord_kmp_prefs` SharedPreferences
 * file) and SecureTokenStore (EncryptedSharedPreferences).
 *
 * `SettingsStore.ConcertLocation` continues to resolve through the
 * typealias to the nested [com.parachord.shared.settings.SettingsStore.ConcertLocation]
 * data class — no call-site changes needed.
 */
typealias SettingsStore = com.parachord.shared.settings.SettingsStore

/**
 * File-scoped typealias to keep call sites that did
 * `com.parachord.android.data.store.SyncSettings` resolving cleanly
 * after the move to shared/commonMain.
 */
typealias SyncSettings = com.parachord.shared.sync.SyncSettings

/**
 * Kotlin file-scoped typealiases don't expose nested classes of the
 * aliased type at call sites — `SettingsStore.ConcertLocation` only
 * worked when `SettingsStore` was a real class in this module. Re-export
 * the nested data class as a top-level alias so existing call sites
 * resolve `ConcertLocation(...)` via this package import.
 */
typealias ConcertLocation = com.parachord.shared.settings.SettingsStore.ConcertLocation
