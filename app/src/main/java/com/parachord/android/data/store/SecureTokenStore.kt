package com.parachord.android.data.store

/**
 * Phase 9B Stage 3: [SecureTokenStore] is now a shared interface in
 * `com.parachord.shared.store.SecureTokenStore`, with an Android
 * implementation at [com.parachord.shared.store.AndroidSecureTokenStore]
 * (EncryptedSharedPreferences over Android Keystore — same behavior the
 * old app-module class implemented). This file-scoped typealias
 * preserves source compatibility for code that still imports
 * `com.parachord.android.data.store.SecureTokenStore`.
 *
 * security: C4 — encrypt tokens at rest.
 */
typealias SecureTokenStore = com.parachord.shared.store.SecureTokenStore
