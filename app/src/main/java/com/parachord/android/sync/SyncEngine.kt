package com.parachord.android.sync

/**
 * Source-compatibility shim. The real implementation moved to
 * [com.parachord.shared.sync.SyncEngine] in the sync extraction
 * phase. The typealias preserves the ~10 Android consumers
 * (Koin module, ViewModels, repositories, schedulers) that import
 * the bare `SyncEngine` name from this package.
 *
 * Constructor signature change: `settingsStore: SettingsStore` →
 * `settingsStore: SyncSettingsProvider` (the shared interface).
 * `SettingsStore` already implements `SyncSettingsProvider`, so
 * existing Koin bindings that pass `get<SettingsStore>()` keep
 * resolving — Koin auto-upcasts to the interface parameter.
 */
typealias SyncEngine = com.parachord.shared.sync.SyncEngine
