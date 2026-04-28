package com.parachord.android.sync

/**
 * Source-compatibility shim. The real implementation moved to
 * [com.parachord.shared.sync.AppleMusicSyncProvider] in the sync extraction
 * phase. The typealias preserves consumers (notably `SyncEngine.kt` and
 * Koin bindings) that import the bare name from this package.
 *
 * The Android-side constructor signature shrunk: prior `SettingsStore`
 * parameter was unused and is dropped in the shared version. Koin
 * binding updated accordingly.
 */
typealias AppleMusicSyncProvider = com.parachord.shared.sync.AppleMusicSyncProvider
