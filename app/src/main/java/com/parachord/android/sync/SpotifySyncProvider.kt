package com.parachord.android.sync

/**
 * Source-compatibility shim. The real implementation moved to
 * [com.parachord.shared.sync.SpotifySyncProvider] in the sync extraction
 * phase. The typealiases below preserve consumers (notably
 * `SyncEngine.kt` and `SyncViewModel.kt`) that import the bare
 * `SyncedX` / `SpotifySyncProvider` names from this package.
 *
 * The Android-side constructor signature shrunk: prior `SettingsStore`
 * and `OAuthManager` parameters were unused after Phase 9E.1.8
 * (per-API auth + global `OAuthRefreshPlugin`) and are dropped in the
 * shared version. Koin bindings updated accordingly.
 */

typealias SpotifySyncProvider = com.parachord.shared.sync.SpotifySyncProvider

typealias SyncedTrack = com.parachord.shared.sync.SyncedTrack
typealias SyncedAlbum = com.parachord.shared.sync.SyncedAlbum
typealias SyncedArtist = com.parachord.shared.sync.SyncedArtist
typealias SyncedPlaylist = com.parachord.shared.sync.SyncedPlaylist
